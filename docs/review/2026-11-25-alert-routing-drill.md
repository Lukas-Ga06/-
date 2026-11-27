# 告警路由与送达演练（2026-11-25）

> 快照日期：2026-11-25 ｜ 验证范围：Alertmanager 路由（severity / 抑制 / 兜底）、恢复通知、
> 「Prometheus 规则 → Alertmanager → 接收端」全链路、以及 `alerts.yml` 能否被裸 Prometheus 直接加载
> ｜ 环境：Windows 10 / Windows PowerShell 5.1 / Prometheus 3.16.0 / Alertmanager 0.28.1 / Node 20.17；
> 所有进程只绑定 `127.0.0.1`，不触碰业务实例、不产生外网请求
> ｜ 结论：**链路通，7 项判定全 PASS，退出码 0**；但接收端是本地 Sink（证据标 `LOCAL-SINK`），
> 「人收到了」仍未验 —— 那需要三个真实 webhook 与主/备值班姓名（§7）。

## 0. 结论速览

| # | 问题 | 结论 |
| --- | --- | --- |
| 1 | 规则会不会 firing | 会：5 条合成规则全部 `pending → firing` |
| 2 | firing 有没有按 severity 路由 | 有：`critical → oncall-critical`、`warning` 与「漏打 severity」→ `oncall-group`、`info → daily-digest` |
| 3 | 通知里字段齐不齐 | 齐：`severity` 正确，`summary` 与 `runbook_url` 均存在（逐字段判定，不是目测） |
| 4 | 抑制规则生不生效 | 生效：同实例的 `TravelPlanOutcomeFailed` 未进入任何通知 |
| 5 | **人能收到吗** | **未验**：接收端是本地 Sink；真实渠道必须走 §7 的人工确认 |
| 6 | 恢复通知发不发 | 发；但受 `group_interval: 5m` 约束（见 §4.1），不是「恢复即通知」 |
| 7 | 裸 Prometheus 能直接加载 `alerts.yml` 吗 | **不能**（它是 PrometheusRule CRD）；抽出 `spec.groups` 后 18 条通过（见 §4.3） |

## 1. 这次补的是哪一段（before → after）

| 环节 | 2026-11-25 白天（[closeout 快照](2026-11-25-external-blockers-closeout.md) §4） | 本次（晚） |
| --- | --- | --- |
| 规则 | 18 条，3 条真实 firing 过 | 抽出 `spec.groups` 后 `promtool check rules`：18 条通过 |
| 路由 | **仓库里没有 Alertmanager 配置** | [alertmanager.yml](../operations/alertmanager.yml)：severity 路由 + 兜底 + 抑制，渲染后 `amtool check-config` 通过 |
| 接收端 | 没有 | 本地 [webhook Sink](../../scripts/alert-webhook-sink.mjs)（演练）／真实 webhook（待给） |
| 送达证据 | 只证明「规则会 firing」 | 逐条判定「哪条告警、几秒后、哪个 receiver、字段对不对、恢复有没有发」 |
| 值班人 | 无 | **仍无**（人工输入，不属工程可代填范围） |

一句话：from「规则会响」到「消息确实被送到某个接收端且内容正确」——**这一步做完了；下一步是把 Sink 换成你的手机。**

## 2. 落地资产

| 文件 | 作用 |
| --- | --- |
| [alertmanager.yml](../operations/alertmanager.yml) | 路由唯一权威：severity 三段 + 兜底 + 抑制；webhook 用 `${ALERT_WEBHOOK_*}` 占位（Alertmanager 不展开环境变量，必须渲染） |
| [render-alertmanager-config.mjs](../../scripts/render-alertmanager-config.mjs) | 环境变量 → 渲染 → `--check` 调 `amtool check-config`；缺变量时拒绝写出（避免「半个配置」；日志只打脱敏 URL） |
| [alerts-rules-extract.mjs](../../scripts/alerts-rules-extract.mjs) | `alerts.yml`（CRD）→ 裸 Prometheus 可加载的 `rule_files`，逐字剥离缩进、不重排 |
| [alert-webhook-sink.mjs](../../scripts/alert-webhook-sink.mjs) | 本地接收端：每个 POST 落盘（`alerts.jsonl` 摘要 + `deliveries/` 原始 payload） |
| [alert-routing-drill.ps1](../../scripts/alert-routing-drill.ps1) | 一条命令编排全部：语法校验 → 起三进程 → 判定 7 类行为 → 写证据；只停自己启动的 PID 子树 |

本地工具：Prometheus 3.16.0（仓库 `.tools/` 已有）、Alertmanager 0.28.1（本次下载到 `.tools/alertmanager/`，均被 gitignore）。

## 3. 实跑结果

证据目录：`.evidence/alert-routing-20261125-184004/`（`summary.md`、`checks.json`、`alerts.jsonl`、
`deliveries/`、三份进程日志）｜ 命令：`powershell -ExecutionPolicy Bypass -File scripts\alert-routing-drill.ps1`
｜ **退出码 0，13 项判定全 PASS**。

### 3.1 判定项

| # | 检查 | 结果 |
| --- | --- | --- |
| C0a | 生产规则（抽出 `spec.groups` 后）`promtool check rules` | PASS：`SUCCESS: 18 rules found` |
| C0b | 渲染后的 `alertmanager.yml` `amtool check-config` | PASS：1 route / 1 inhibit rule / 3 receivers / 0 templates |
| C0c | 演练用 Prometheus 配置 `promtool check config` | PASS：5 条合成规则 |
| C0d | Sink / Alertmanager / Prometheus 就绪 | PASS（三者均 ready） |
| C0e | 合成规则 `pending → firing` | PASS（`/api/v1/alerts` 观察到 `state=firing`） |
| C0f | 四路 firing 通知全部到达 | PASS（4/4） |
| C1 | critical 路由 + ≤120s 送达 + 字段齐全 | PASS：**9.8 s**，`summary` / `severity` / `runbook_url` 齐全 |
| C2 | 抑制生效 | PASS：`TravelPlanOutcomeFailed` 未出现在任何通知里 |
| C3 | warning → `oncall-group` | PASS |
| C4 | info → `daily-digest` | PASS |
| C7 | 漏打 severity → `oncall-group`（兜底路由） | PASS |
| C5 | critical / warning 收到 resolved | PASS：reload → 首条 **249.6 s** |
| C6 | info 不发 resolved（`send_resolved: false`） | PASS：0 条 |

### 3.2 送达时间线（UTC；本地时间 = UTC+08:00）

| # | 收到时间 | receiver | status | 告警 |
| --- | --- | --- | --- | --- |
| 1 | 2026-11-25T10:40:32.175Z | oncall-critical | firing | TravelAgentServerDown |
| 2 | 2026-11-25T10:40:32.183Z | daily-digest | firing | TravelRouteProbeInfo |
| 3 | 2026-11-25T10:41:22.169Z | oncall-group | firing | TravelRouteProbeUnlabeled |
| 4 | 2026-11-25T10:41:22.179Z | oncall-group | firing | TravelRouteProbeWarning |
| 5 | 2026-11-25T10:45:32.166Z | oncall-critical | resolved | TravelAgentServerDown |
| 6 | 2026-11-25T10:46:22.170Z | oncall-group | resolved | TravelRouteProbeUnlabeled |
| 7 | 2026-11-25T10:46:22.174Z | oncall-group | resolved | TravelRouteProbeWarning |

首次 firing（Prometheus 观测，UTC）：`2026-11-25T10:40:22Z`。

### 3.3 延迟观察

| 链路 | 实测 | 由什么决定 |
| --- | --- | --- |
| 规则 firing → critical 送达 | 9.8 s | `group_wait: 10s` |
| 规则 firing → warning / 兜底送达 | ≈ 60 s | `group_wait: 1m` |
| critical 首次通知 → resolved | 299.99 s | `group_interval: 5m` |
| warning 首次通知 → resolved | 300.00 s | `group_interval: 5m` |
| 规则翻恒空（`/-/reload`）→ 首条 resolved | 249.6 s | 距上次同组通知满 5m 才发 |
| 全流程（含等待） | ≈ 7 分 13 秒 | — |

结论：两处「等这么久」都不是延迟故障，是配置语义（`group_wait` / `group_interval`）。真实渠道到位后这两段等待
同样存在——**值班响应时限要按「首次通知时间」算，不能按「故障发生时间」算**。

## 4. 三个语义坑（这次用实测钉住）

### 4.1 `resolved` 受 `group_interval` 约束，不是「恢复就立刻通知」

第一次跑演练时按 120s 等 `resolved`，超时也没等到（那次演练因此没跑完就被终止）；把等待放宽到 6 分钟后全 PASS，
resolved 在 249–300s 后到达。原因不是丢消息：Alertmanager 的 `group_interval` 同时约束「新告警」与「已恢复告警」——
刚发过 firing 的组，要等到 `group_interval` 到点才会外发 resolved。

运维含义：
- 故障恢复后 5 分钟内没收到 resolved，**先看 group 状态再判断链路坏没坏**；
- 想更快收到 resolved，要调 `group_interval`，不是 `repeat_interval`；
- 演练脚本的 `-ResolveWaitSeconds`（默认 360s）就是为这条语义准备的，不要改小。

### 4.2 抑制只影响通知，不影响告警状态

`inhibit_rules`（`TravelAgentServerDown` 抑制同 `instance` 的其它 critical）实测生效：
`TravelPlanOutcomeFailed` 与抑制源同时 firing、同时 resolved，但**从未出现在任何通知里**。

这意味着：如果以后做告警状态页，必须能显示「已抑制」，否则值班人会以为「这条规则没触发」。

### 4.3 `alerts.yml` 是 CRD，而且组数文档写错了

`promtool check rules docs/operations/alerts.yml` 直接报错：

```
field apiVersion not found in type rulefmt.RuleGroups
field kind not found in type rulefmt.RuleGroups
field metadata not found in type rulefmt.RuleGroups
field spec not found in type rulefmt.RuleGroups
```

它是 Prometheus Operator 的 `PrometheusRule`；仓库注释里「把 `spec.groups` 放进 `rule_files`」一直是**手抄步骤**，
没人验证过，而手抄少一条就是一条永久静默的告警。本次把这一步做成
[alerts-rules-extract.mjs](../../scripts/alerts-rules-extract.mjs)，顺带发现：

- 抽出后 `SUCCESS: 18 rules found`；
- 文档此前写「18 条，**5 组**」，实际是 **8 组**（availability / planning / confirm / sse / external / latency / llm / rag）——
  已更正 [alert-routing.md](../operations/alert-routing.md) §1 的组数，并在 [runbook](../operations/runbook.md)
  附录 C.1 增加「裸 Prometheus 先用脚本抽出 `groups:` 再校验」的检查项。

## 5. 仍未覆盖（不要把本快照读成「告警已万无一失」）

1. **真实渠道送达**：没有 webhook 就证明不了「人收到了」；`-Mode synthetic` 能证明 Alertmanager 把 firing 与
   resolved 都送到了端点（两条 `Notify success`，S1–S4），但「人看到了」仍要人工确认。
2. **单实例**：没有做 Alertmanager 高可用（gossip 集群）与多副本去重；实例挂了就一条通知都没有。
3. **静默（silence）流程未演练**：只在配置注释里要求「静默必须写原因与到期时间」。
4. **没有通知模板（`templates/`）与升级策略**：`repeat_interval` 只是重复提醒，不包含「未 ack 自动升级」。
5. **Prometheus ↔ Alertmanager 无 TLS / 鉴权**：当前仅本机回环，多机部署必须补。
6. **IM 机器人限额未验**：频率限制、消息长度上限、群机器人被限流后的表现都没测。

## 6. 复现

```powershell
# 全链路演练（本地 Sink，约 7 分钟；warning 组 group_wait 1m + resolved 受 group_interval 5m 约束）
powershell -ExecutionPolicy Bypass -File scripts\alert-routing-drill.ps1

# 只看工具链与配置语法，不起进程
powershell -ExecutionPolicy Bypass -File scripts\alert-routing-drill.ps1 -DryRun

# 真实渠道（给了三个 webhook 之后；约 6 分钟，resolved 那条要等 group_interval）
$env:ALERT_WEBHOOK_CRITICAL='https://...'; $env:ALERT_WEBHOOK_WARNING='https://...'; $env:ALERT_WEBHOOK_INFO='https://...'
powershell -ExecutionPolicy Bypass -File scripts\alert-routing-drill.ps1 -Mode synthetic
```

退出码：`0` 全 PASS；`2` 参数/工具链/端口问题；`3` 存在 FAIL；`1` 未预期异常。
证据目录：`.evidence/alert-routing-<时间戳>/`（`summary.md`、`checks.json`、`alerts.jsonl`、`deliveries/`、三份进程日志）。

> **synthetic 分支自测**：真实渠道尚未提供，但 `-Mode synthetic` 这条路径已用本地 Sink 当「真实端点」跑通
> S1–S4（`firing` 10:49:33.818Z → `resolved` 10:54:33.824Z，正好 300s，与 §4.1 的 `group_interval` 一致），
> 证据 `.evidence/alert-routing-20261125-184919/`。换成真实 webhook 只需改环境变量。

## 7. 真实渠道人工确认（拿到 webhook 后填，脚本无法代填）

| 项 | 值 |
| --- | --- |
| critical 渠道类型（IM 机器人 / 电话 / 邮件） |  |
| 收到消息的人 |  |
| 收到时间（与 firing 时间相差） |  |
| 消息是否含 summary / severity / runbook_url |  |
| 是否收到 resolved |  |
| 截图 / 消息 ID |  |

填完本表 + [alert-routing.md](../operations/alert-routing.md) §5 的值班表，第 12 项才算闭环。

相关文档：[告警路由与值班](../operations/alert-routing.md) · [路由配置](../operations/alertmanager.yml) ·
[告警规则](../operations/alerts.yml) · [上线执行清单 §2 第 12 项](../operations/launch-checklist.md) ·
[外部阻断项复核](2026-11-25-external-blockers-closeout.md)