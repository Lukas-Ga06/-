# 高德降级告警实跑（2026-11-24）

> 环境：Windows 11 / JDK 17.0.1 / Prometheus 3.16.0 / Grafana 11.3.0
> 被测服务：`travel-agent-server-0.1.0.jar`（`main` `5898e66` 构建），端口 18088，全新 H2 文件库，固定 `JWT_SECRET`
> 结论：**`TravelExternalProviderDegraded{provider="amap"}` 与 `TravelExternalDegradedHigh` 都从 pending 走到 firing；停止降级后回落为 inactive。** 规则不是「配置了但永远静默」。
> 未覆盖：真实高德断供 / Key 失效（本次注入的是 `connection_error`，真实 `10021` / `INVALID_USER_KEY` 走同一条计数口但未在告警层跑过）、Grafana 通知通道与值班路由、值班人指派、多实例聚合。

## 1. 为什么要补这一项

上线清单 §2 第 12 项「回滚与告警」在 2026-11-23 只实跑了后端掉线（`TravelAgentServerDown`，P0）。高德降级这条 P1 一直是「规则写了、Dashboard 有面板、但从没人见它响过」。

这类告警最典型的失效方式不是误报，而是**寂静**：表达式引用了不存在的指标、`for` 窗口比 `rate` 窗口短、分母守卫把有流量写成无流量——配置文件和单元测试看起来都正常，只有真正注入故障才会暴露。原文 `observability.md` §9 甚至写着「T3 未合入前 `TravelExternalDegradedHigh` 不会触发」，而 T3 早已合入：口径本身过期了，也就是这次要一并改正的地方。

## 2. 环境与故障注入

| 项 | 取值 |
| --- | --- |
| 被测服务 | `travel-agent-server-0.1.0.jar`，`--server.port=18088`，默认 profile（H2 文件库，Flyway 全量迁移） |
| JWT | `JWT_SECRET` 固定为随机 48 字节 Base64；不固定则重启后 Prometheus 的 Bearer token 立刻失效（[2026-11-23 演练](2026-11-23-monitoring-and-rollback-drill.md) §1.4 已踩过） |
| 高德 | `--travel.amap.key=drill-dummy-key`、`--travel.amap.base-url=http://127.0.0.1:1`：连接立即被拒 → `connection_error`；不触达真实高德、不消耗额度 |
| Nominatim | 兜底地址是代码内常量（`CityGeoLocator#NOMINATIM_SEARCH`），用 JVM 级 `-Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=1` 让它也失败，原因见下 |
| Prometheus | 3.16.0，`127.0.0.1:19090`，`scrape_interval=5s`；`docs/operations/alerts.yml` 转成裸 `rule_files`（`promtool check rules` → 18 rules，加载后 8 组 / 18 条） |
| Grafana | 11.3.0，`127.0.0.1:3300`；数据源指向该 Prometheus，仪表盘 `travel-agent-slo` 导入成功（uid `travel-agent-slo`） |
| 流量 | `GET /api/trips/map`，账号下只有一个目的地「儋州」（不在 `CityAtlas` 图集、不在旅行目录 → 必然走联网定位）；每次请求产生 1 次高德地理编码尝试 |

**为什么要同时挡住 Nominatim**：`CityGeoLocator#locateCity` 在高德失败后会退回 Nominatim，真实联网下 Nominatim 能解析「儋州」并把结果写进 `TripMapService` 的 `coordCache`——降级只发生一次，凑不出持续速率，告警自然永远不会响。这不是告警的缺陷，是「降级一次就自愈」的正常行为；要验证「持续降级」，必须让两条路径都持续失败。

## 3. 时间线

时间为本机本地时间（UTC+8），告警状态取自 `GET /api/v1/alerts` 与 `/api/v1/rules`。

| 本地时间 | 事件 / 状态 |
| --- | --- |
| 20:03:14 | 实例启动完成（`Started TravelAgentApplication in 31.074 seconds`，Tomcat 18088） |
| 20:03:23 | 单次 `GET /api/trips/map`：`高德定位失败: 儋州 - Connection refused` → `external_degraded_total{provider="amap",reason="connection_error"} 1` |
| 20:03:37 | 降级流量开始：前 ~3 分钟约 3 次/秒，之后约 2 次/秒 |
| 20:04:02 | `TravelExternalDegradedHigh` 进入 **pending**（ratio ≈ 0.93；`activeAt=12:04:02.6Z`） |
| 20:05:02 | `TravelExternalProviderDegraded{provider="amap"}` 进入 **pending**（`rate[15m]` ≈ 0.27，阈值 0.2；`activeAt=12:05:02.6Z`） |
| 20:14:08 | `TravelExternalDegradedHigh` 进入 **firing**（`for: 10m`，首次评估落在 20:14:02 之后） |
| 20:20:03 | `TravelExternalProviderDegraded` 进入 **firing**（`for: 15m`，首次评估落在 20:20:02 之后；此时 `rate[15m]` ≈ 1.48） |
| 20:20:37 | 停止降级流量：累计 **1735** 次 `/api/trips/map`（1624 次 200 + 111 次被应用限流 429） |
| 20:31:05 | `TravelExternalDegradedHigh` 回落 **inactive**（`rate[10m]` 窗口在 20:30:37 后排空，比率归 0） |
| 20:33:38 | `TravelExternalProviderDegraded` 回落 **inactive**（`rate[15m]` 随窗口滑出降到 0.2 以下；20:35:41 查得 `rate15m = 0`） |

> 两条告警的 firing 时刻都精确落在 `activeAt + for` 之后的第一轮评估上，说明 `for` 语义与本机 30s 评估间隔一致，没有额外延迟。
> **恢复不需要人工介入**：停止注入后，10 分钟窗口的比率告警 10 分 28 秒后自动解除，15 分钟窗口的单 provider 告警 13 分 01 秒后解除（先跌破阈值、后窗口排空）。

## 4. 证据

采样时刻 20:06:20（降级进行中）与 20:21:00（降级停止后）：

```text
# 进行中
external_degraded_total{provider="amap",reason="connection_error"} 315.0
http_server_requests_seconds_count{...,uri="/api/trips/map"} 315
sum by (provider) (rate(external_degraded_total[15m])){provider="amap"} 0.46

# 停止后（终值）
external_degraded_total{provider="amap",reason="connection_error"} 1624.0
http_server_requests_seconds_count{...,status="200",uri="/api/trips/map"} 1624
http_server_requests_seconds_count{...,status="429",uri="/api/trips/map"} 111
```

- **1:1 计数**：`/api/trips/map` 的 200 响应数与 `external_degraded_total` 最终完全相等（**1624 / 1624**），说明每个真正走到高德的请求恰好一次降级，没有重复计数也没有漏记。另有 111 次被应用自身限流器拒绝（429）——这些请求根本没走到高德，因此不产生降级计数，这是正确口径而不是漏记。
- **Grafana 面板链路**：同一表达式经 Grafana `/api/ds/query` 返回 `1.44`（5 分钟窗口），仪表盘 `travel-agent-slo` 导入成功——值班看到的曲线就是这条。
- **告警体**（firing 时原样摘录，保留真实值）：

```json
{"labels": {"alertname": "TravelExternalProviderDegraded", "provider": "amap",
            "severity": "warning", "team": "travel-agent"},
 "annotations": {"summary": "外部依赖 amap 持续降级（> 0.2 次/秒）",
                 "runbook_url": "https://github.com/OWNER/travel-agent/blob/main/docs/operations/runbook.md#7-travelexternaldegradedhigh-p1"},
 "state": "firing", "activeAt": "2026-11-24T12:05:02.600697855Z", "value": "1.475977653631285e+00"}
```

- 应用日志（每次降级一行 `INFO`，可按 `provider` 检索）：

```text
2026-11-24T20:03:23.402+08:00  INFO [] 22884 --- [map-locate-1] c.t.agent.service.AmapGeocodeClient : 高德定位失败: 儋州 - I/O error on GET request for "http://127.0.0.1:1/v3/geocode/geo": Connection refused
2026-11-24T20:03:23.428+08:00  INFO [] 22884 --- [map-locate-1] c.travel.agent.service.CityGeoLocator : Nominatim 定位不可达（已跳过）: 儋州
```

- 证据目录（本机、不入库）：`.evidence/amap-alert-drill/`（`prometheus.yml`、`rules.yml`、`alerts-timeline.jsonl`、`rules-now.json`、`metrics-now.txt`、`grafana-panel-query.json`、`app/app.log`、`traffic.log`）。

## 5. 判读与发现

1. **两条规则都真的会响**，且只有它们响：其余 16 条规则全程 inactive，没有连带误报；`TravelLowHistogramMissing` 也没误报（histogram 已开）。
2. **`TravelExternalProviderDegraded` 的「首个 firing 延迟」实测 16 分 26 秒**（持续降级 20:03:37 → firing 20:20:03）：`rate[15m]` 要先在窗口里攒够 0.2 次/秒，`for: 15m` 再等 15 分钟。也就是说这条 P1 的语义是「已经持续降级」，不是「刚开始降级」。对「当天处置」够用，但**不要**把它当早期预警；要更早发现需要另一条短窗口规则（例如 `increase(external_degraded_total[5m]) > 60`），本次不改阈值，只把延迟写进口径。
3. **分母守卫按设计工作**：`TravelExternalDegradedHigh` 用 `clamp_min(http_server_requests_seconds_count, 1e-9)` + `and sum(...) > 0`，抓取端点自身的流量不会把比率稀释到阈值以下（实测 ratio 稳定在 0.93 左右，阈值 0.05）。
4. **抓取凭证是运行期依赖**：`/actuator/prometheus` 需要 Bearer JWT，token 与 `JWT_SECRET` 绑定。本机若不固定密钥，**任何一次重启都会让监控哑掉**（不是告警逻辑问题，是采集链路问题）。生产 compose 已强制注入 `JWT_SECRET`；临时环境复现监控必须显式给固定值。
5. **规则文件本身可被裸 Prometheus 直接加载**：`docs/operations/alerts.yml` 去掉 `PrometheusRule` 外壳后 `promtool check rules` 通过（18 条），与「8 组 / 18 条」的口径一致，无需为演练另写规则。
6. **应用限流器会截断注入速率**：稳态阶段按 ~2 次/秒打请求时，约 6% 被应用自身的限流（429）拒绝，实际降级速率约 1.5 次/秒。这不影响告警（仍远高于 0.2 次/秒），但说明「降级速率的上限由真实业务流量决定」：灌流量灌不上去时先看 429，不要误判成埋点丢了。
7. **`runbook_url` 还是 `OWNER` 占位**：firing 告警里带的是 `https://github.com/OWNER/travel-agent/...`，部署前必须按 `alerts.yml` 文件头的 `sed` 替换成真实组织名，否则值班点进去是 404。这是部署动作项，不是本次演练的缺陷。

## 6. 未覆盖与剩余动作

| 项 | 状态 |
| --- | --- |
| 真实高德断供 / Key 失效 | 未跑：本次 reason 是 `connection_error`，真实 `10021` / `INVALID_USER_KEY` 是 HTTP 200 + `status=0` → `bad_response`。同一计数口、同一告警表达式，但没在告警层验证过 |
| Grafana 通知通道（邮件 / IM）与值班路由 | 未做：本机 Grafana 只用于看板验证，没有接 Alertmanager 与通知渠道 |
| 灰度值班人 | **未定**（这是 #12 剩下的另一半，归属人） |
| 多实例聚合 | 未验：单实例演练，`sum by (provider)` 在多实例下的表现按设计应成立 |
| 生产网络下的抓取抖动 | 未验：本机 `scrape_interval=5s`，生产网络延迟未模拟 |

## 7. 复现

```bash
# 1) 起被测服务：固定密钥，高德指向黑洞，Nominatim 用 JVM 代理挡住
export JWT_SECRET=$(python -c "import os,base64;print(base64.b64encode(os.urandom(48)).decode())")
java -Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=1 -jar travel-agent-server/target/travel-agent-server-0.1.0.jar \
  --server.port=18088 --travel.amap.key=drill-dummy-key \
  --travel.amap.base-url=http://127.0.0.1:1 --travel.amap.connect-timeout-ms=500

# 2) 注册抓取账号与流量账号；建一个图集外的目的地行程，然后反复打 /api/trips/map
# 3) 起 Prometheus：rule_files 指向 docs/operations/alerts.yml 的 spec.groups，抓取端带 Bearer
# 4) 观察：curl -s 127.0.0.1:19090/api/v1/alerts | jq '.data.alerts[] | {n:.labels.alertname,s:.state}'
```

完整脚本与时间线见本机 `.evidence/amap-alert-drill/`（`start-app.sh`、`setup.sh`、`traffic.sh`、`monitor.sh`）。