# 五项外部阻断复核与收口（2026-11-25）

> 快照日期：2026-11-25 ｜ 验证范围：灰度/公网上线清单里仍挂着的 5 项外部依赖——高德 MCP Key、Android 真机 ×2、
> 法务定稿（LICENSE/隐私页 5 处占位）、灰度值班与告警路由、真实 S3 凭证/端点
> ｜ 环境：Windows 10 / JDK 17.0.1 / Maven 3.9.16 / Node 20.17 / 本机 H2 + 隔离端口；
> 高德 Key 取 `travel-agent-server/.env.local`（32 位 Web服务 Key，指纹 `56257ebd`，不落盘）
> ｜ 结论：**5 项里 1 项当天复验通过——MCP 真实链路不是"未验"，而是口径过期；其余 4 项仍缺外部输入，
> 但每项都已收敛成"一张表 / 一条命令"**。

## 0. 结论速览

| # | 阻断项（本轮原始写法） | 复核结论 | 证据 |
| --- | --- | --- | --- |
| 1 | 高德 MCP Key：MCP 真实链路未验（REST 已实测） | **已验，口径过期**：2026-11-22 已用真实 Web服务 Key 跑通 12 工具 + 真模型调用；今天 19.54s 复跑 2/2 通过。仍未覆盖的是**生产部署路径**（容器内 `MCP_ENABLED=true`）与 MCP 限流时序 | §1 |
| 2 | Android 真机 ×2：§5.1 矩阵未跑 | **未跑**：本机没有设备通道（`adb devices` 为空，2026-11-21 已记录）；仿真 21/21 不算真机 | §2 |
| 3 | 法务定稿：LICENSE/隐私页 5 处占位 | **未定稿**：5 个字段 + 5 项法务裁定为空；已做成可回填签署单 | §3 |
| 4 | 灰度值班人：告警路由未指派 | **未指派**：规则能 firing，但没有 Alertmanager 路由、没有接收人、没有送达证据 | §4 |
| 5 | 真实 S3 凭证/端点：只验了本地协议桩 | **未验真端点**：桩验通上传/读回/删除；真端点仍需凭证。今天把验收做成一条命令，并用桩自测通过（标 STUB-ONLY） | §5 |

一句话给决策者：**这不是"还差很多代码"，是"还差四个外部输入 + 一次部署态验收"。**

## 1. 高德 MCP：已验，之前记错了

### 1.1 为什么清单里会写"未验"

三份文档叠出了这个结论，逐层看：

| 日期 | 文档 | 当时的说法 | 问题 |
| --- | --- | --- | --- |
| 2026-11-22 | [MCP 12 工具逐项核对](2026-11-22-mcp-live-and-fr03.md) | 真实 Key 下 12/12 工具返回可用数据；真模型自主选用 `mcp_amap_maps_weather` | 结论是"到此为止"（已验） |
| 2026-11-23 | [高德 REST 真实 Key 验收](2026-11-23-amap-real-key-probe.md) §5 | "MCP 场景的 `AMAP_MAPS_API_KEY` 仍未验（历史记录里仍是 `USERKEY_PLAT_NOMATCH`）" | **比 11-22 的证据旧一天**；沿用了 11-19 的失败记录，没看 11-22 的成功记录 |
| 2026-11-24 | [发布记录](../operations/release-record.md) 两条 | 沿用"高德 MCP Key 未完成" | 把上一行又抄了一遍 |

判断不靠文档靠原始证据：`.evidence/mcp-live/mvn-run.log`（11-22，`Tests run: 2`，22.63 s）与
`.evidence/mcp-live-probe/raw.json`（12 个工具的原始返回）仍在，内容可逐条核对。

### 1.2 今天（2026-11-25）复跑

```powershell
$env:MCP_IT = "true"; $env:MCP_ENABLED = "true"; $env:AMAP_MAPS_API_KEY = "<Web服务 Key>"
cd travel-agent-server; mvn -B -Dtest=McpAmapLiveTest test
```

| 项 | 结果 |
| --- | --- |
| 用例 | `Tests run: 2, Failures: 0, Errors: 0, Skipped: 0`，**19.54 s**，BUILD SUCCESS |
| 工具数 | `tools/list` 12 个，全部注册为 `mcp_amap_*`、`readOnly=true` |
| 真实返回样本 | 西湖→杭州东站 `7713 m`；杭州东站→西湖 `7388 m`、换乘「地铁1号线(萧山国际机场--湘湖)」；`maps_text_search` 首条 `B023B13L9M 杭州西湖风景名胜区 / 龙井路1号`；`maps_search_detail` `120.121358,30.222692`；`maps_weather` **2026-11-25 多云 14/6℃**（当天预报，不是缓存） |
| 证据 | `.evidence/mcp-live-20261125/mvn-run.log`（37 KB） |

> 这次跑的是桥接层（`McpToolBridge` → `McpConnection` → stdio 子进程 → 高德），与生产代码同一条路径；
> "模型自己选用 MCP 工具"那一层由 11-22 的快照覆盖（`toolCalls: mcp_amap_maps_weather success=True`）。
> 两层合起来才算"真实链路已验"，今天没有重复烧模型费用重跑第二层。

### 1.3 MCP 仍未覆盖的三块（不要把本节读成"全验"）

1. **生产部署路径**：`docker-compose.yml` 的 `MCP_ENABLED` 默认 `false`、`AMAP_MAPS_API_KEY` 默认空；
   只有 `.env.production.example` 置 `true`。本机 `.env` 两项为空 —— 也就是说**容器栈里的 MCP 段从来没跑过**，
   `npx` 首次冷启动下载、容器内出网、`cmd /c` 包装在容器里的行为都还没验。
2. **10021 限流时序**：MCP 分支已实现一次退避（`AmapDataService.executeMcpWithQpsRetry`），
   但真实 Key 下的 MCP 限流触发→重试→降级路径没有定向复现（11-22 那次撞限流时该重试还没写）。
   REST 分支的 10021 有实测（20 并发命中 9 次）。
3. **降级告警口径**：`external_degraded_total{provider="amap"}` 由 REST 与 MCP 共用，MCP 单独降级是否
   触发同一告警没有单独验（11-24 的演练只覆盖了 REST 侧）。

### 1.4 关闭条件（半小时）

```powershell
# .env 追加（不入库）
AMAP_KEY=<生产「Web服务」Key>
AMAP_MAPS_API_KEY=<同一枚 Key，或给 MCP 单独申请>
MCP_ENABLED=true
docker compose up -d --build
docker compose logs backend | Select-String '注册 12 个工具'
# 登录后：GET /api/system/status → mcpEnabled=true, mcpConnected=true, mcpToolCount=12
```

通过标准：启动日志出现「MCP 服务器 [amap] … 已接入，注册 12 个工具」，status 里 `mcpToolCount=12`，
一次 `INVOKE_TOOL`（问「杭州天气」）返回真实预报。注意现有 Key 是**个人配额**，只能验收、不能当生产容量依据。

## 2. Android 真机 ×2：确实没跑

| 项 | 事实 | 依据 |
| --- | --- | --- |
| 设备通道 | `adb devices` 为空；无 `xcrun` / `idevice_id` | [设备与弱网验收](2026-11-21-device-network-acceptance.md) §1 |
| 已做的替代 | WebKit/iPhone 预设 + Chromium CDP 弱网/离线：21/21 通过 | 同上 |
| 口径 | 仿真通过 **不等于** 真机通过；软键盘、微信 WebView、PWA 安装只有真机能验 | [上线清单 §0](../operations/launch-checklist.md) |

新增 [Android 真机验收模板](android-device-acceptance-template.md)：设备信息九字段、§5.1 十维矩阵（含截图编号）、
报告骨架、三类"只有真机会暴露"的问题。设备到位后按模板填，产出 `docs/review/<日期>-android-device.md` 即闭环。

需要你做的：两台设备（一台原生 Android 11+，一台国产 ROM 或微信内置浏览器）+ 约 30 分钟。

## 3. 法务定稿：5 个字段 + 5 项裁定

"5 处占位"指 [隐私政策](../legal/privacy-policy.md) §1 表的五行：
**运营主体、注册地址、隐私联系邮箱、生效日期、版本号**（`privacy-policy.md:13-17`）。
它们还出现在 `:5`、`:125`、`:129`（引用性描述，替换时保留句子语义）。

另有 5 项不是"填空"而是"裁定"，工程无法代填：日志/备份留存期（`:101`、`:134`、`data-deletion.md:227`）、
删除审计保存期（`data-deletion.md:224`）、法务留存例外（`:228`）、用户请求响应时限（`:125`）、
许可证专有/开源（[licensing](../legal/licensing.md) §3）。

已做成 [法务定稿签署单](../legal/signoff-sheet.md)：每个字段给了行号、提供方、格式要求与回填列；
裁定项给了"需要的结论"列。运营/法务一次性填完，我按单替换并跑 `docs-check` 即闭环。

工程侧提醒：**生效日期不能早于上线日**；`[通知方式]` 若选"邮件"等于新增一项能力（`users` 表没有邮箱字段）。

## 4. 灰度值班与告警路由：规则会响，没人收到

| 环节 | 状态 |
| --- | --- |
| 规则 | 18 条，5 组，都带 `severity/summary/runbook_url`（[alerts.yml](../operations/alerts.yml)） |
| 规则实跑 | 3 条真实 firing 并恢复（ServerDown / 外部降级 / 高等级降级） |
| 路由 | **缺**：仓库内没有 Alertmanager 或通知渠道配置 |
| 接收人 | **缺**：runbook 只写"升级后端负责人/值班主管"，没有名字与渠道 |
| 送达证据 | **缺**：三次演练证明"规则会 firing"，没证明"人收到了" |

新增 [告警路由与灰度值班](../operations/alert-routing.md)：按 `severity` 的路由模型（critical 立即 / warning 1m /
info 日报）、Alertmanager 骨架（含抑制规则）、`amtool` 合成告警验证命令、两周一轮的值班表与升级链字段。

需要你做的：一个能收 critical 的 webhook（或邮箱）+ 主/备值班姓名。给完我落地配置并跑一次合成告警留证。

> **2026-11-25 晚追记（工程侧已收口，快照原文保留）**：[alertmanager.yml](../operations/alertmanager.yml)
> 已落地（severity 路由 + 抑制 + 兜底），`scripts/alert-routing-drill.ps1` 跑通「规则 firing → 路由 → 抑制 →
> 送达 → resolved」并留证（接收端为本地 Sink，标 `LOCAL-SINK`）；`alerts.yml` 的 `spec.groups` 抽出后
> `promtool check rules` 18 条通过（顺带更正组数口径：§4 表里的「5 组」实为 8 组）。**剩下仍是原来说的两样人工输入**：
> 三个真实 webhook + 主/备值班姓名（给 URL 后 `-Mode synthetic` 一条命令确认投递，人确认收到才算闭环）。
> 证据见 [告警路由演练](2026-11-25-alert-routing-drill.md)。

## 5. 真实 S3：从"桩"到"一条命令"

### 5.1 已验证的边界（不夸大）

| 步骤 | 本地协议桩结果 |
| --- | --- |
| 上传 `POST /api/trips/{id}/photos` | 200，对象写入桩 |
| 读回 `GET /api/photos/{id}/raw` | 200，sha256 与上传一致 |
| 删除照片 → 再读 | DELETE 200 → raw 404 |
| 删除行程 | 200 |

桩**不是**对象存储：不校验签名、不分片、没有版本控制/生命周期，也不能证明 HTTPS 与真实网关的兼容性。

### 5.2 今天新增：`scripts/s3-real-e2e.ps1`

一条命令完成"起隔离实例（`PHOTOS_STORE=s3`）→ 注册登录 → 建行程 → 上传 → 读回比对 sha256 → 删除 → 404 校验 →
删行程"，并把每步 HTTP 码、sha256、summary.md、server 日志写进 `.evidence/s3-real-<时间戳>/`。

| 设计点 | 行为 |
| --- | --- |
| 端点守卫 | `localhost/127.0.0.1` 默认**拒绝执行**（退出 2），防止把桩结果当真实端点验收；桩自测必须显式 `-AllowLocalStub`，summary 标 `STUB-ONLY` |
| 凭证 | 只从参数或 `S3_ACCESS_KEY/S3_SECRET_KEY` 环境变量进，不写日志/证据；证据里 token 脱敏 |
| 兼容开关 | `-PathStyle`（MinIO 等）、`-ChecksumValidation true/false`（网关不返回 CRC32 时关掉） |
| 进程安全 | 只 `taskkill /PID <本脚本启动的 PID> /T`，不枚举其它 java |

自测（2026-11-25，桩）：

```powershell
powershell -ExecutionPolicy Bypass -File scripts\s3-real-e2e.ps1 `
  -Endpoint http://127.0.0.1:9090 -Bucket travel-photos -PathStyle -AllowLocalStub `
  -AccessKey test -SecretKey test -ChecksumValidation false
```

结果：**STUB-ONLY 全通过**（上传 `photoId=1`、读回 sha256 `c414cd0e…`、删除 200 / 再读 404），退出码 0，
证据 `.evidence/s3-real-20261125-181235/`。自测同时把一个**已知坑**又踩了一遍：桩不返回校验头时，
SDK 2.25 的默认尾校验会抛 `ArrayIndexOutOfBoundsException`（上传 500「照片保存失败」）——
这正是 11-24 新增 `TRAVEL_PHOTOS_S3_CHECKSUM_VALIDATION` 的原因；真实网关若同样不返回校验头，需要显式关掉。

### 5.3 真端点验收（凭证到手后 10 分钟）

```powershell
powershell -ExecutionPolicy Bypass -File scripts\s3-real-e2e.ps1 `
  -Endpoint https://<minio/oss/cos 端点> -Bucket <已存在的桶> -Region <区域> `
  -AccessKey $env:S3_ACCESS_KEY -SecretKey $env:S3_SECRET_KEY -PathStyle
```

通过标准：`summary.md` 里 0/1/2/3/4/5/6 全 PASS，退出码 0，且 summary 的"本地桩标记 = 否"。
注意桶必须**已存在**（脚本不建桶：S3 建桶要签名，curl 做不了）；AWS S3 不要加 `-PathStyle`。

### 5.4 真端点仍未覆盖

签名/鉴权细节（脚本走应用自己的 SDK，不自造签名）、HTTPS 证书链、版本控制/生命周期下的历史对象、
分片上传（大文件）、多实例并发写同一 bucket、供应商侧备份与跨区复制。这些是"验过一次"也仍需巡检的部分。

## 6. Go/No-Go（基于本轮复核）

| 级别 | 判断 | 还差什么 |
| --- | --- | --- |
| A 内部演示 | **具备** | 无 |
| B 受控灰度 | **未具备** | ① 值班人 + critical 渠道（人，0.5h）② 生产 MCP Key + 容器内启动一次（0.5h）③ 灰度若收照片：真端点 S3 或先 `PHOTOS_STORE=local`（0.5–1h） |
| C 公网 v1 | **未具备** | B 全部 + Android 真机 ×2 + 法务 5 字段/5 裁定 + 发布记录（含回滚与 RTO） |

排序建议（按"解除阻断的性价比"）：**值班路由（1h，零成本，解除 B 的一半）→ 生产 MCP Key（0.5h）→ S3 真端点（1h）
→ 法务（外部排期，尽早送审）→ Android 真机（最重，0.5 天但可放在灰度期间做，因为 B 级不要求真机门禁）。**

## 7. 需要你给的五样东西

| # | 输入 | 形式 | 给到后我做什么 |
| --- | --- | --- | --- |
| 1 | 生产高德「Web服务」Key（或确认 `56257ebd` 这枚可用于生产） | 环境变量 / 密钥管理 | 起容器栈验 MCP 12 工具 + `mcpToolCount` |
| 2 | 两台 Android 设备 | 设备 + 30 分钟 | 按模板跑 §5.1 矩阵，产出验收快照 |
| 3 | 法务 5 字段 + 5 裁定 | 填 [signoff-sheet](../legal/signoff-sheet.md) | 替换占位、跑 docs-check、更新上线清单 |
| 4 | critical 告警接收渠道 + 主/备值班人 | webhook / 姓名 | 落地 Alertmanager 路由 + 合成告警留证 |
| 5 | S3 endpoint / bucket / AK / SK / region / 是否 path-style | 环境变量 | 跑 `s3-real-e2e.ps1`，产出真端点证据 |

## 8. 复现命令

```powershell
# 1) MCP 12 工具（真实 Key；约 20 s）
$env:MCP_IT='true'; $env:MCP_ENABLED='true'; $env:AMAP_MAPS_API_KEY='<Web服务 Key>'
cd travel-agent-server; mvn -B -Dtest=McpAmapLiveTest test

# 2) S3 真端点（凭证自备；见 §5.3）
powershell -ExecutionPolicy Bypass -File scripts\s3-real-e2e.ps1 -Endpoint <...> -Bucket <...> -AccessKey <...> -SecretKey <...> -PathStyle

# 3) 文档门禁（新增/改动了 4 篇文档后必须跑）
node scripts/docs-check.mjs
```

相关文档：[上线执行清单](../operations/launch-checklist.md) · [发布记录](../operations/release-record.md) ·
[告警路由与值班](../operations/alert-routing.md) · [法务签署单](../legal/signoff-sheet.md) ·
[Android 真机模板](android-device-acceptance-template.md)