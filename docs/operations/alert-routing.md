# 告警路由与灰度值班

> 状态：**路由与送达链路已落地并实测（接收端 = 本地 Sink）；真实渠道与值班人仍待指派** ｜ 更新：2026-11-25 晚
> ｜ 依据：[alerts.yml](alerts.yml)（22 条规则 / 9 组）、[alertmanager.yml](alertmanager.yml)（路由权威）、
> [alert-routing-drill.ps1](../../scripts/alert-routing-drill.ps1)（一条命令跑通送达并留证）
> ｜ 一句话：Prometheus 能算、Alertmanager 能按 severity 分、链路能送到一个接收端并留下消息时间戳；
> 现在缺的只剩「把人接进去」——三个真实 webhook 与两行值班表。

## 1. 现状（2026-11-25 晚核对）

| 项 | 现状 | 依据 |
| --- | --- | --- |
| 告警规则 | 22 条 / 9 组，`severity`、`summary`、`description`、`runbook_url` 齐全；抽出 `spec.groups` 后 `promtool check rules` 通过（2026-11-25 新增 §8 可靠性 4 条） | [alerts.yml](alerts.yml)、[演练快照](../review/2026-11-25-alert-routing-drill.md) |
| 路由配置 | **已落地**：[alertmanager.yml](alertmanager.yml)（critical / warning / info + 兜底 + 抑制规则），渲染后 `amtool check-config` 通过 | 同上 |
| 送达链路 | **已实测**：合成告警走完「规则 firing → 按 severity 路由 → 抑制 → 送达 → 恢复」，接收端逐条留下接收时间与消息体 | [演练快照](../review/2026-11-25-alert-routing-drill.md) |
| 真实接收人 | **仍缺**：三个 webhook 与主/备值班人尚未指派，演练接收端是本地 Sink（证据标 `LOCAL-SINK`） | §5、§6 |
| 真实渠道送达 | **未验**：没有真实 webhook 就无法证明「人收到了」；给了 URL 后用 §4.2 一条命令确认 | §4.2 |

结论：`launch-checklist.md` §2 第 12 项的工程侧已经收口，**剩下的是两个人工输入**（渠道 URL、值班姓名），
不是代码或配置工作。

## 2. 路由模型（按 severity，不按告警名）

| severity | 典型规则 | 首次通知 | 重复 | 渠道 | 响应要求 |
| --- | --- | --- | --- | --- | --- |
| `critical` | ServerDown / PlanOutcomeFailed / ConfirmSuccessRateLow / SseAbnormalDisconnectHigh | 立即（`group_wait: 10s`） | 每 1h 重复，直到 ack | 值班手机（IM 强提醒 / 电话） | 5 分钟内认领，30 分钟未恢复升级 |
| `warning` | PlanDegradedHigh / LowHistogramMissing / 外部降级 | 1m 内（`group_wait: 1m`） | 每 4h | 值班群 | 当班内处理 |
| `info` | ConfirmConflictHigh | 聚合进日报 | — | 工单 / 群内摘要 | 次工作日 |
| 无 `severity` | 新规则漏打标签 | 1m 内进值班群 | 每 4h | 值班群 | 当班内处理（兜底，防静默丢弃） |

分组键：`alertname` + `instance`（避免一次故障刷屏）；`group_interval: 5m`；静默必须写原因与到期时间。

两个容易踩的语义（都已写进配置注释）：

1. **`resolved` 也被 `group_interval` 约束**：刚发过 firing 的组，恢复通知要等 5 分钟到点才外发。
   演练实测恢复通知比故障恢复晚数分钟——这是聚合语义，不是丢消息（见演练快照）。
2. **抑制只对通知生效，不对存储生效**：被 `TravelAgentServerDown` 抑制的 critical 仍留在 Alertmanager
   的告警状态里，只是不进通知。排查时不要因为「没收到」就认为「没触发」。

## 3. 已落地资产与部署

### 3.1 仓库内资产

| 文件 | 作用 | 谁来跑 |
| --- | --- | --- |
| [alertmanager.yml](alertmanager.yml) | 路由唯一权威：severity 路由、抑制规则、三个 receiver；webhook 一律 `${ALERT_WEBHOOK_*}` 占位 | 部署时渲染 |
| [render-alertmanager-config.mjs](../../scripts/render-alertmanager-config.mjs) | 用环境变量渲染上面的模板；拒绝残留占位符；`--check` 顺带跑 `amtool check-config`；日志只打脱敏后的 URL | 我 / 运维 |
| [alerts-rules-extract.mjs](../../scripts/alerts-rules-extract.mjs) | 把 `alerts.yml` 的 `spec.groups` 抽成裸 Prometheus 的 `rule_files`（CRD 与 rule_files 的桥） | 我 / 运维 |
| [alert-webhook-sink.mjs](../../scripts/alert-webhook-sink.mjs) | 本地最小接收端：把每个 webhook POST 落盘（摘要 JSONL + 原始 payload），用于在真实渠道到位前验证链路 | 我 |
| [alert-routing-drill.ps1](../../scripts/alert-routing-drill.ps1) | 一条命令跑完整链路（drill 模式）或用真实 webhook 做投递确认（synthetic 模式） | 我 / 值班 |

`.tools/` 下的 Prometheus / Alertmanager 是本地演练工具（已 gitignore，不入库）。
仓库里只保留**配置与脚本**，不保留任何真实 webhook 值。

### 3.2 生产部署（真实渠道到位后）

```powershell
# 1) 三个 webhook 只进环境变量，绝不写进仓库
$env:ALERT_WEBHOOK_CRITICAL = '<值班 IM critical 机器人 webhook>'
$env:ALERT_WEBHOOK_WARNING  = '<值班群机器人 webhook>'
$env:ALERT_WEBHOOK_INFO     = '<日报 / 工单 webhook>'

# 2) 渲染 + 语法校验（产物已 gitignore）
node scripts/render-alertmanager-config.mjs -Out alertmanager.rendered.yml --check

# 3) 启动单实例（--cluster.listen-address= 关闭 gossip 端口，避免与其它实例抢 9094）
.tools\alertmanager\alertmanager-0.28.1.windows-amd64\alertmanager.exe `
  --config.file alertmanager.rendered.yml `
  --storage.path <持久化目录> `
  --web.listen-address 127.0.0.1:9093 `
  --cluster.listen-address=
```

Prometheus 侧只需要四行（指向 Alertmanager）：

```yaml
alerting:
  alertmanagers:
    - timeout: 10s
      static_configs:
        - targets: ['127.0.0.1:9093']
```

**`alerts.yml` 不能直接交给裸 Prometheus**：它是 Prometheus Operator 的 `PrometheusRule`（顶层
`apiVersion/kind/metadata/spec`），而 `rule_files` 只认顶层 `groups:`。以前这一步靠手抄 `spec.groups`
（少抄一条就是一条永久静默的告警），现在一条命令：

```powershell
node scripts/alerts-rules-extract.mjs -Out alerts-rules.yml
.tools\prometheus\prometheus-3.16.0.windows-amd64\promtool.exe check rules alerts-rules.yml
# → SUCCESS: 18 rules found
```

### 3.3 真实渠道不可用时的降级

没有 webhook 也能先跑通链路：`alert-routing-drill.ps1` 的 drill 模式把三个 receiver 全指向本地 Sink，
不产生任何外网请求、零成本。**它证明的是配置与链路，不证明「有人收到了」**，证据会显式标 `LOCAL-SINK`。

## 4. 送达验证（不是「配完就算」）

### 4.1 本地全链路演练（一条命令，约 7 分钟）

```powershell
powershell -ExecutionPolicy Bypass -File scripts\alert-routing-drill.ps1
```

它做的事：校验规则与路由语法 → 起本地 Sink / Alertmanager / Prometheus（隔离端口）→ 注入 5 条合成告警
→ 判定路由、抑制、字段与延迟 → 把规则改成恒空并 `POST /-/reload` → 判定恢复通知 → 写证据
（`summary.md` / `checks.json` / `alerts.jsonl` / 每个 webhook POST 的原始 payload / 三份进程日志）。

检查项与 2026-11-25 实跑结果见[演练快照](../review/2026-11-25-alert-routing-drill.md)。
演练只绑定 `127.0.0.1`，不碰业务进程，收尾只停自己启动的 PID 子树。

### 4.2 真实渠道确认（拿到 webhook 之后）

```powershell
$env:ALERT_WEBHOOK_CRITICAL='https://...'; $env:ALERT_WEBHOOK_WARNING='https://...'; $env:ALERT_WEBHOOK_INFO='https://...'
powershell -ExecutionPolicy Bypass -File scripts\alert-routing-drill.ps1 -Mode synthetic
```

synthetic 模式用**真实路由配置**起本地 Alertmanager，注入一条合成 critical 告警（`summary` 明确写
「演练，非真实故障」），判定四件事：配置能启动（S1）→ 告警被接受（S2）→ 你的端点返回 2xx（S3：日志 `Notify success`）
→ 推 `endsAt` 后 `resolved` 也投递成功（S4：第二条 `Notify success`）。全程约 5–6 分钟，因为 S4 受 §2 的
`group_interval` 约束；脚本不会在 resolved 之前退出，所以演练消息不会永久挂在群里。

脚本只能证明「请求送到了你的端点」；**「人看到了」必须由值班人在证据目录的 summary 里回填**。

### 4.3 通过标准（三条同时满足才算闭环）

1. 接收端在 firing 后 2 分钟内收到消息，内容含 `summary`、`severity`、`runbook_url`；
2. 恢复后收到 `resolved`（`send_resolved: true`；注意受 §2 的 `group_interval` 约束）；
3. 记录的接收人姓名与 §5 值班表一致。

## 5. 值班表（灰度期，两周一轮；填完即关闭该项）

| 周次（周一–周日） | 主值班 | 联系方式 | 备值班 | 联系方式 | 交接时间 |
| --- | --- | --- | --- | --- | --- |
| 2026-12-01 ~ 2026-12-07 | | | | | 周一 10:00 |
| 2026-12-08 ~ 2026-12-14 | | | | | 周一 10:00 |

配套必须同时定下来的三件事（否则值班表是空的）：

| 项 | 值 | 说明 |
| --- | --- | --- |
| 反馈渠道 | | 灰度用户报问题的入口（群 / 表单 / 邮箱），要能进值班人的通知流 |
| 升级链 | | 主值班 → 后端负责人 → 值班主管；每级给 15/30 分钟阈值（runbook 已按此写） |
| 灰度名单 | | 谁在用；出 P0 时需要能主动通知到 |

## 6. 关闭条件与归属

| 项 | 归属 | 关闭动作 |
| --- | --- | --- |
| 路由配置 + 抑制规则 | 我 | ✅ 已落地 [alertmanager.yml](alertmanager.yml)：`amtool check-config` 通过 |
| 规则可被裸 Prometheus 加载 | 我 | ✅ 已落地 `alerts-rules-extract.mjs`：22 条（含 2026-11-25 新增 4 条）逐字抽出并 `promtool check rules` 通过 |
| 送达链路（本地） | 我 | ✅ 已跑通并留证，证据标 `LOCAL-SINK` |
| 接收渠道（webhook ×3） | 你 | 给一个能收 critical 的地址（建议独立 critical 通道）＋ 值班群 ＋ 日报入口 |
| 主/备值班人 | 你 | 填 §5 两行 |
| 真实渠道送达 | 你我 | 你给 URL 后跑 §4.2；你确认收到并把截图/时间写进证据目录 summary |
| 升级链与反馈渠道 | 你 / 我 | 写进 [runbook](runbook.md) 与灰度公告 |

填完 §3.2 的三个环境变量与 §5 的两行后，这一项从「未指派」变成「已指派」；§4.2 的人工确认完成才算「已闭环」。

## 7. 已知边界（不要把演练读成「全验」）

1. **单实例**：没有做 Alertmanager 高可用（gossip 集群）与多副本去重；漏发一条就等于漏一次通知。
2. **没有接通知渠道的去重 / 升级策略**：`repeat_interval` 只是重复提醒，不包含「未 ack 自动升级到上级」。
3. **静默（silence）流程未演练**：只在配置注释里要求「静默必须写原因与到期时间」。
4. **真实渠道的限额与模板**：IM 机器人有频率限制、消息长度限制，模板化（`templates/`）未做。
5. **Prometheus 与 Alertmanager 之间没有 TLS / 鉴权**：当前是本机回环；上多机部署必须补。

相关文档：[告警规则](alerts.yml) · [路由配置](alertmanager.yml) · [处置手册](runbook.md) ·
[可观测性](../guide/observability.md) · [上线执行清单 §4.6](launch-checklist.md) ·
[演练快照](../review/2026-11-25-alert-routing-drill.md) · [外部阻断项复核](../review/2026-11-25-external-blockers-closeout.md) §4