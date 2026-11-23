# 容器冷启动 + 真实 LLM 20 连压测（2026-11-23）

> 快照日期：2026-11-23 ｜ 验证范围：WSL2/Docker 冷启动全栈验收 + 真实 LLM 20 连规划压测 ｜ 环境：Windows 10 Home 26H2（build 26300.9974）/ WSL **3.0.5.0**（内核 6.18.58.1-1）/ Docker Desktop **29.9.3** / Compose **v5.7.0** / MySQL 8.4 / JDK 17 / 主机内存 15.8 GB
> 结论：全栈可启动，6 项安全/运维口径通过（H2「404」口径不符、S3 未验）；**20/20 有明确终态、零 429、花费 ¥0.39**；顺带修掉容器 healthcheck 语法错误
> 证据：`.evidence/llm-load-20/`（20 轮原始响应 + `summary.json`，目录在 `.gitignore` 内）
> 口径说明：本文只记录**实际跑出来的结果**；没跑的一律写「未验证」。

## 0. 前置：这台机器的 WSL 是残缺的，不是「没装 Docker」

Docker Desktop 29.9.3 早就装在 `AppData\Local\Programs\DockerDesktop`，起不来的真实原因是引擎缺 WSL2：
`docker info` 报 `Docker Desktop is unable to start`，后端日志给出停机原因 `wsl is not installed`。
两个可选功能用 `Get-CimInstance Win32_OptionalFeature` 查已是 `InstallState=1`，但机器上**没有
`LxssManager`、也没有 `WslService`**，`wsl.exe` 是个「有文件、零输出、exit 0」的空壳——功能骨架在、实现包没落地。

三条路都试过：

| 路径 | 结果 |
| --- | --- |
| `wsl --install --no-distribution` | **403**：`已禁止(403)`，Store 拉取路径被拦 |
| 9 个常用 GitHub 镜像（ghproxy.net / gh-proxy.com / ghfast.top / ghproxy.cc / ghps.cc …） | DNS 可解析，**TCP/TLS 全部不可达**（`code=000`）；对照组 example.com / baidu.com / api.github.com 均 200 |
| DISM 启用功能 + 官方内核 MSI | 功能启用成功（EXIT=3010）；旧内核 MSI 报 `1603 / This update only applies to machines with the Windows Subsytem for Linux` |

最终可行路径：**DISM 启用两个功能 → 重启 → 装 GitHub Releases 的独立 MSI**（`wsl.2.7.14.0.x64.msi`，
实际装出 3.0.5.0）。下载本身也踩了两个坑：单连接只有 **~76 KB/s**；每个分片请求都要经
`api.github.com` 换签名跳转，未认证配额（60 次/小时）耗尽后返回 `403 rate limit exceeded`。
解法是 **12 路分片并行**（4 连接实测 1,365 KB/s，限速按连接而非按总量）+ 配额恢复后**只调一次**
API 换出签名 CDN 链接再直连分片。落地后 **sha256 与官方 digest 一致**、Authenticode 签名有效
（CN=Microsoft Corporation，签发者 Microsoft Code Signing PCA 2024）。

## 1. 容器冷启动验收

`docker compose up -d` 起 mysql 8.4 / redis 7 / backend / web 四个服务，只有 web 发布到宿主机 `8081:80`。

| 验收项 | 结果 | 证据 |
| --- | --- | --- |
| 全栈可启动 | **通过** | mysql、redis `healthy`；backend `Started TravelAgentApplication in 8.228 seconds` |
| `system/status` | **通过** | `llmConfigured=true`、`llmVerified=false`、`llmModel=deepseek-flash`、`amapConfigured=false`、`geoProvider=osm`、`routeProvider=estimated`、`rateLimitEnabled=true`、`timezone=Asia/Shanghai`、`pendingPlanStore=database` |
| demo 账号关闭 | **通过** | `demo/demo123` 登录 → **401**；启动日志 `演示账号已禁用（travel.demo-account.enabled=false）` |
| Secure Cookie | **通过** | `Set-Cookie: travel_agent_token=…; Path=/; Max-Age=259200; Secure; HttpOnly; SameSite=Lax` |
| H2 console 不可达 | **口径不符** | 后端直连 `/h2-console/` → **401**（不是 404，安全链先拦）；经 nginx → **200 HTML**（SPA fallback，不是控制台）。「404」这个验收口径本身需要改 |
| nginx 日志去掉查询参数 | **通过** | `log_format without_args`；`?ticket=SECRETXYZ123` 请求在日志里只留 `"GET /api/system/status HTTP/1.1" 401`，查询串未落盘 |
| Redis 限流 | **通过（有 caveat）** | 第 11 次登录起 **429**「操作过于频繁，请 300 秒后再试」；Redis 内可见 `ratelimit:ip\|ip:172.18.0.5\|POST auth` 计数 |
| S3 照片存储 | **未验证** | `.env` 的 `PHOTOS_STORE=local`，本次没有 S3 端点/凭证 |

## 2. 三个发现

### 2.1 后端容器 healthcheck 语法错误（已修复）

`travel-agent-server/Dockerfile` 把 exec-form 与 shell 混写：

```dockerfile
HEALTHCHECK … CMD ["curl", "-fsS", "http://localhost:8080/actuator/health"] || exit 1
```

Docker 因此退化成 shell 形式，容器内 `/bin/sh` 去找名为 `[curl,` 的命令：

```
/bin/sh: 1: [curl,: not found
```

后果是应用已经 `{"status":"UP"}`，容器却 **永久 unhealthy**（重试 5 次后固定失败），任何
`depends_on: condition: service_healthy` 的编排都会被卡死。改成纯 exec form 后重新 build，
**15 秒转 healthy**。`curl -f` 本身失败即非零退出，不需要 `|| exit 1`。

### 2.2 限流身份在 nginx 后面塌成一个桶

外部请求全部计入 `ip:172.18.0.5`（nginx 容器 IP，计数 15），而不是客户端 IP。原因链：

1. nginx 用 `proxy_set_header X-Forwarded-For $remote_addr`（只写真实来源，防伪造，方向是对的）；
2. 从宿主机/Docker 网络进来的客户端，nginx 看到的源 IP 是 `172.18.0.1`（bridge 网关）；
3. `.env` 里 `RATE_LIMIT_TRUSTED_PROXIES=172.16.0.0/12` 覆盖了 `172.18.0.1`，后端把它当代理跳跳过；
4. 没有剩余不可信跳，后端退回 `remoteAddr`（= nginx `172.18.0.5`）。

**后果**：凡源 IP 落在 `172.16.0.0/12` 的客户端共用一个桶，一个滥用者可锁死其他人；公网真实 IP
不受影响。**本机测出的 429 因此不代表按真实客户端隔离**，灰度前需要用外部源 IP 复验。

### 2.3 Docker Desktop 不能从 shell 里启动

冷启动过程中 Docker Desktop 自行退出两次。日志显示后端在 `21:48:33` 仍正常应答 `/time`，
`21:48:44` 之后进程消失、npipe 一并消失；同期我用 `docker compose exec` 的命令把持久 shell 撞到超时
被杀。推断：Docker Desktop 由 shell 里的 `Start-Process` 拉起，shell 被终止时被一起带走。
改用 **WMI `Win32_Process.Create`** 启动后（脱离 shell 进程树），后续所有检查稳定通过。

## 3. 真实 LLM 20 连压测（P0-B 完成标准）

驱动：`.e2e/llm-load-20.mjs`（串行 20 条不同规划消息，2 秒间隔，命中 429 自动等待重试）。
目标：容器里的后端 `http://localhost:8081`（经 nginx），`LLM_MODEL=deepseek-flash`。

**结果：20/20 都有明确终态，红线「无终态 = 0」达成。**

| 指标 | 数值 |
| --- | --- |
| 终态分布 | `NEED_CONFIRMATION` 14 ｜ `COMPLETED` 6 |
| 无明确终态 | **0**（列表为空） |
| 429 轮次 | 0 |
| 耗时 | min **2,663 ms** ／ median **31,389 ms** ／ max **45,569 ms** |
| 实际花费 | 余额 **¥10.66 → ¥10.27**，即 **¥0.39** |

关于 6 轮「快 + COMPLETED」：不是绕过人工确认，而是**槽位不足主动反问**——响应里
`planId=null`、`confirmation=null`、`missingSlots=["partySize"]`，正文是「开始规划前，还需要确认：
几个人一起…」。没有发生任何写操作，因此不需要确认门；`NEED_CONFIRMATION` 的 14 轮都正常生成了待确认方案。

## 4. 仍未验证

- **S3**（`PHOTOS_STORE=local`）：需要端点、桶名、access key / secret 才能验。
- **H2 console 的「404」口径**：实际是后端 401、nginx 200 SPA。要么改验收口径，要么给安全链加一个
  显式 404 规则（避免用 401/SPA 掩盖「路径不存在」）。
- **高德 MCP**：`AMAP_MAPS_API_KEY` 为空，`10021` 退避与降级告警仍只有 REST 侧证据。
- **限流按真实客户端隔离**：见 §2.2，需从公网源 IP 复验。