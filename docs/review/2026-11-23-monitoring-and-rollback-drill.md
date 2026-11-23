# 监控告警与回滚演练（2026-11-23）

> 环境：Windows 11 / JDK 17 / MySQL 8.4.10（本机 3307）/ Prometheus 3.16.0 / Grafana 11.3.0
> 被测服务：`travel-agent-server`（含 V24），H2 文件库（监控实例）与 MySQL 演练库两种后端各用各的实例
> 结论：**后端掉线告警按真实规则触发并被 Grafana 读到；V24 之后回滚到 N-1 代码可用，数据库不需要恢复。**
> 未覆盖：高德限流/断供告警的实跑（缺高德 Web 服务 Key）、告警值班人、真实生产磁盘与网络。

## 1. 监控告警实跑

### 1.1 组成

- Prometheus：`.tools/prometheus/prometheus-3.16.0.windows-amd64/prometheus.exe`，监听 `127.0.0.1:19090`，抓 `http://127.0.0.1:18088/actuator/prometheus`，间隔 5s。
- 规则：`docs/operations/alerts.yml` 转成裸 Prometheus 的 `rule_files`（`.evidence/monitoring/rules.yml`），加载 **8 组 / 18 条**规则。
- 抓取认证：`/actuator/prometheus` 需要登录态，Prometheus 用 Bearer JWT（V24 后 token 带 `uid/ver`）。
- Grafana：`.tools/grafana/grafana-v11.3.0`，监听 `127.0.0.1:3300`；数据源 `Prometheus`，仪表盘 `travel-agent-slo`（`docs/operations/grafana-travel-agent.json`）。

### 1.2 故障注入与结果

| 时间（本地） | 动作 / 状态 |
| --- | --- |
| 15:15:57 | 停掉后端进程（18088） |
| ~15:18 | Prometheus `up{job="travel-agent-server"} == 0` 成立，`TravelAgentServerDown` 进入 **pending** |
| 15:21:46 | 持续 3 分钟（规则 `for: 3m`）后进入 **firing**；`activeAt=2026-11-23T07:18:19Z` |
| 15:23:33 | 后端重启完成（`Started ... in 19.249 s`，含 H2/Flyway/validate） |
| 15:24:27 | 抓取恢复 `up=1`；`/api/v1/alerts` 回到空，告警解除 |

同时观察到一个次要告警 `TravelLowHistogramMissing` 在整机掉线时进入 pending：`absent(http_server_requests_seconds_bucket)` 在唯一目标掉线时也为真。它 `for: 15m`，本次在恢复前未升级为 firing。**处置口径**：看到 `TravelAgentServerDown` 时先按掉线处理，不要把 `TravelLowHistogramMissing` 误判为埋点缺失。

### 1.3 Grafana 验证

- 数据源探活：`GET /api/datasources/proxy/uid/<uid>/api/v1/query?query=up` → `up=1`（Grafana → Prometheus 链路通）。
- 仪表盘导入：`POST /api/dashboards/import` → `uid=travel-agent-slo`，`imported=true`。
- 未做：面板截图归档（本机 Grafana 仅用于演练）、Grafana 告警通道（邮件/IM）与值班路由。

### 1.4 环境注意

- 本机实例未设置 `JWT_SECRET`，JwtService 每次启动生成一次性密钥 → **重启后 Prometheus 的 Bearer token 全部失效**（本次即因此重新注册了抓取账号并更新 token）。生产 compose 已强制注入 `JWT_SECRET`，不存在该问题；本地复现监控时必须显式给固定密钥。
- 抓取账号是普通注册用户（只读指标端点），不要复用管理员账号。

## 2. 回滚演练（V24 → N-1）

### 2.1 场景与准备

- 目标：验证「**V24 这种加列迁移**发布后，直接回退 N-1 代码是否仍可用」——即 Flyway forward-only + `ddl-auto=validate` 的真实边界。
- N-1 代码：`8cdd5b2`（V24 之前最后一个提交）单独 worktree 构建 jar。
- 数据库：`ta_drill_restore5`（V24 schema、23 条迁移、200 用户/142,200 行关键数据）复制为 `ta_drill_rollback`。
- N-1 实例：`--spring.profiles.active=mysql`，端口 18089，关闭 LLM/MCP/高德/演示账号。

### 2.2 结果

| 项 | 结果 |
| --- | --- |
| Flyway（N-1 classpath 只有 V1–V23） | `Successfully validated 23 migrations`：history 里的 V24 被视为 future，不报错、不回滚 |
| Hibernate `validate` | 通过（`users.token_version` 是旧实体没有的列，validate 忽略多余列） |
| 启动 | `Started TravelAgentApplication in 14.775 seconds` |
| 业务冒烟（旧代码 + 新 schema） | 注册新用户 200 → 创建行程 200（trip id 12001）→ 列表 200 |
| 结论 | 加列型迁移**可以只回退代码、不恢复数据库**；回滚 RTO ≈ 15s |
| 破坏性迁移（删列/改类型） | 仍只能恢复发布前备份（见 backup-restore §8），RTO 取备份恢复演练值 ≈18s + 丢数窗口 |

注意：N-1 代码签发的 token 没有 `uid/ver`，V24 代码会拒绝；但回滚后运行的是 N-1，二者自洽。若回滚后再次前滚到 V24，期间 N-1 签发的 token 会失效一次（与首次上线相同），需公告。

## 3. 证据与复现

- 证据目录（本机、不入库）：`.evidence/monitoring/`（`alerts-firing.json`、`up-after-stop.json`、`alerts-after-recovery.json`、`rules-now.json`、`targets-fixed.json`、`grafana/`）、`.evidence/rollback/n1-start.log`。
- 复现监控：

```bash
# 1) 起后端（固定 JWT 密钥，否则重启后抓取 token 失效）
JWT_SECRET=<base64-48B> java -jar travel-agent-server/target/travel-agent-server-0.1.0.jar --server.port=18088
# 2) 注册一个只读抓取账号，把 Bearer token 写进 prometheus.yml
# 3) 起 Prometheus / Grafana，停后端 3 分钟以上观察 alert 状态
```

- 复现回滚：用 N-1 提交构建 jar，指向 V24 数据库直接启动，跑注册/建行程/列表三条请求。