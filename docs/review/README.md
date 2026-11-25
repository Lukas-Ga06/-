# 评审与验收快照（Review Snapshots）

> 状态：目录规范 | 适用版本：0.1.0（Spring Boot 3.5.16 / Node 20） | 最后验证：2026-11-19 | 事实来源：docs/review/、docs/guide/documentation.md

本目录保存**带日期的评审 / 验收 / 决策快照**。快照回答「当时用什么环境、验了什么、结论是什么」，**不代表当前版本的行为保证**；当前事实一律以 [docs/guide](../guide/README.md) 为准。

## 1. 快照规范

- 文件名：`YYYY-MM-DD-主题.md`（日期为验证日期，主题用短横线连接，如 `2026-11-19-acceptance.md`）。
- 开头必须写清四件事：**快照日期 / 验证范围 / 环境 / 结论**；结论有保留时写明「仍待办」。
- **保留原文**：后续修复不回头改写旧快照；结论变化用新快照（新日期、新文件）表达，旧快照可加一行「已被 <新快照> 取代」。
- 事实必须可复核：源码断言标 `文件:行`，命令/数字标来源（CI、`.e2e/`、`.evidence/`）；未核实的外部事实标「待确认」。
- 快照不做承诺：通过 ≠ 发布保证，单测通过 ≠ 统计效果提升，模拟器通过 ≠ 真机通过。
- 不要把「状态：当前事实」写进快照；这是 [docs/guide](../guide/README.md) 的元数据口径。

## 2. 头部模板

```markdown
# 标题

> 快照日期：YYYY-MM-DD ｜ 验证范围：一句话 ｜ 环境：OS / JDK / Node / 数据库 ｜ 结论：一句话（仍待办写在这里）
```

## 3. 快照清单

| 文件 | 日期 | 验证范围 | 结论 |
| --- | --- | --- | --- |
| [2026-11-14-quality-review.md](2026-11-14-quality-review.md) | 2026-11-14 | 全栈质量门禁、后端/安全、前端三门禁、运行时实测 | 核心闭环可跑、三门禁全绿；上线前缺并发锁、凭证边界、可重复质量门禁 |
| [2026-11-19-acceptance.md](2026-11-19-acceptance.md) | 2026-11-19 | V2.2 FR-01~FR-10、PWA、稳定性、外部 Provider | FR-01~FR-10 全部验收；MCP 真实 Key 链路与高德 QPS 重试待办 |
| [2026-11-19-fix-verification.md](2026-11-19-fix-verification.md) | 2026-11-19 | 验收遗留 P1/P2/P3/P5/P6、真实道路几何、部署回归 | 五项均已修复并复验；真机与 MCP 真实链路仍待人工 |
| [2026-11-19-mobile-pwa.md](2026-11-19-mobile-pwa.md) | 2026-11-19 | iPhone WebKit / Pixel Chromium 仿真、PWA 安装、离线恢复 | 自动化 32 项 0 FAIL；真机系统级安装待人工 |
| [2026-11-19-resilience.md](2026-11-19-resilience.md) | 2026-11-19 | 并发、慢接口、第三方失败、约 12 分钟长跑 | 总判定可接受（无 5xx / 挂死 / 串号）；两项 P1 需整改 |
| [2026-11-19-provider-rag-decision.md](2026-11-19-provider-rag-decision.md) | 2026-11-19 | FR-11 个性化 RAG、FR-12 实时酒店/餐饮 Provider | 两者均延后（P1）；本轮只做「参考价 + 来源 + 深链」防守项 |
| [2026-11-19-project-improvement-audit.md](2026-11-19-project-improvement-audit.md) | 2026-11-19 | 当前工作区源码、CI 配置、本地构建结果 | 优先发布可信 / 性能 / 契约 / 可观测，而非继续横向加功能 |
| [2026-11-20-e2e-and-gates.md](2026-11-20-e2e-and-gates.md) | 2026-11-20 | 后端 verify 门禁、前端四道门禁、Playwright e2e、回顾口径 | 本地全绿（540 tests，e2e 8/8）；远端 CI、真机与真实模型仍待验 |
| [2026-11-20-stage-c-assistant.md](2026-11-20-stage-c-assistant.md) | 2026-11-20 | 结构化结果卡、会话隔离、停止生成、可点击建议、过程折叠 | 四项落地（465 用例 + e2e 8/8）；最近会话列表与阶段 E 待做 |
| [2026-11-21-stage-e-empty-state-a11y.md](2026-11-21-stage-e-empty-state-a11y.md) | 2026-11-21 | 五处空状态统一、axe 无障碍扫描、对比度修正 | 空状态与无障碍达标（468 用例 + e2e 13/13）；CSS 拆分与视觉回归未做 |
| [2026-11-21-stage-e-css-split-visual.md](2026-11-21-stage-e-css-split-visual.md) | 2026-11-21 | global.css 页面级拆分、11 张关键页面截图基线 | 拆分逐像素等价（11/11）、482 用例全绿；剩余共享层分批、基线未进 CI |
| [2026-11-21-stage-d-conflict.md](2026-11-21-stage-d-conflict.md) | 2026-11-21 | 冲突提示「此行程已在其他位置更新」：版本字段、409 处理、版本主动对齐 | 冲突可识别/提示/刷新，后端 verify 与前端 482 用例全绿；真实双端并发未验 |
| [2026-11-21-recent-conversations.md](2026-11-21-recent-conversations.md) | 2026-11-21 | §8.6 最近会话列表与继续对话（两个只读接口 + 助手抽屉） | 列表/回填可用（547 后端 + 490 前端用例全绿）；会话 scope 与会话管理未做 |
| [2026-11-23-first-screen-budget.md](2026-11-23-first-screen-budget.md) | 2026-11-23 | 首屏单 chunk 依赖归因、表单栈分割实验 | 首屏 597,968/600,000 B（余 2,032 B）；manualChunks 拆表单栈反而涨到 684 KB，已回退；v1 不得新增首屏依赖 |
| [2026-11-23-monitoring-and-rollback-drill.md](2026-11-23-monitoring-and-rollback-drill.md) | 2026-11-23 | Prometheus/Grafana 告警实跑、V24 之后回退 N-1 的回滚演练 | 后端掉线 3 分钟后 `TravelAgentServerDown` 真实 firing 并恢复；Grafana 数据源/仪表盘可用；N-1 jar 在 V24 schema 上启动 + 业务冒烟通过，回滚 RTO≈15s |
| [2026-11-23-amap-real-key-probe.md](2026-11-23-amap-real-key-probe.md) | 2026-11-23 | 高德 REST 真实 Key 验收：地理编码/路线/跨城 POI/并发限流 | Key 可作 Web 服务使用；跨城同名 POI 与 polyline 正常；20 并发复现 9 次 `10021`；MCP 的 `AMAP_MAPS_API_KEY` 仍未验 |
| [2026-11-23-container-cold-start-and-llm-load.md](2026-11-23-container-cold-start-and-llm-load.md) | 2026-11-23 | WSL2/Docker 冷启动全栈验收 + 真实 LLM 20 连规划压测 | 全栈可启动，6 项通过（H2 口径不符、S3 未验）；20/20 有明确终态、零 429、花费 ¥0.39；修掉容器 healthcheck 语法错误。**2026-11-24 复核：run-18 是失败文案被误记成功（假绿），已修** |
| [2026-11-24-gray-release-blockers.md](2026-11-24-gray-release-blockers.md) | 2026-11-24 | 灰度阻断项：run-18 假绿、目的地抽取、限流按真实客户端隔离、S3 | 压测四分类后旧证据 planned 14 / asked_back 5 / **failed 1**；20 连重跑 15 planned + 5 asked_back + 0 failed；13 轮脏/错目的地全部改正；限流四个来源各自分桶、伪造 XFF 不生效；S3 见 §4 |
| [2026-11-21-conversation-scope.md](2026-11-21-conversation-scope.md) | 2026-11-21 | 会话 scope（V21 迁移 + scope 字段 + 回填带作用域 + 前端恢复行程绑定） | scope 端到端打通、跨页面续聊恢复 tripId；真实 MySQL V1..V21 全绿（550 后端用例）；会话管理与同会话并发控制未做 |
| [2026-11-21-stage-e-css-split-batch2.md](2026-11-21-stage-e-css-split-batch2.md) | 2026-11-21 | global.css 第二批拆分（首页 / 行程 / 足迹）+ 视觉基线复核 | 逐规则等价（0 丢失 / 0 声明变化）、11/11 基线 0 像素差、入口 CSS −13.5%；7 类必须留在 global 的规则已列明 |
| [2026-11-21-device-network-acceptance.md](2026-11-21-device-network-acceptance.md) | 2026-11-21 | WebKit/iPhone 预设移动端、CDP 弱网与离线、同账号多端并发 | 8 条新增用例全过（21/21）；iOS 真机不可验；顺带把 a11y 门禁从「数据依赖」修成确定性并修掉一处真实对比度缺陷 |
| [2026-11-22-real-model-acceptance.md](2026-11-22-real-model-acceptance.md) | 2026-11-22 | 真实 DeepSeek key 下的规划/意图链路、模型运行态自报 | `llmVerified=true` 链路打通（23s 出规划）；默认模型名 `deepseek-chat` 在售列表里已不存在，需 `deepseek-flash`；余额仅 ¥0.80 只做 2 次调用 |
| [2026-11-22-remote-ci-first-run.md](2026-11-22-remote-ci-first-run.md) | 2026-11-22 | 远端 CI 首跑、9 类环境漂移的归因与修复 | main 上 7/8 job 绿；剩余 2 项卡在仓库设置开关（Dependency graph / Code scanning）；顺带修掉生产容器的时区隐患 |
| [2026-11-22-mcp-live-and-fr03.md](2026-11-22-mcp-live-and-fr03.md) | 2026-11-22 | 高德 MCP 12 工具的逐项业务输出核对 + FR-03 真模型复跑 | 12/12 工具真实可用（2 个上游参数被丢弃已定位到源码）；FR-03 真模型打通（mcp_amap_maps_weather 被模型自主选用），暴露 4 个缺口：内置工具缺参数名、研究循环不给 FINAL、MCP 路线分支无 10021 重试、状态页不报 MCP |
| [2026-11-22-p0-execution.md](2026-11-22-p0-execution.md) | 2026-11-22 | P0-A/P0-B/P0-C/P0-D 与 P1-E/P1-G 的代码、门禁、隔离 E2E、账号注销与 UI 收口 | 隔离 E2E 桌面 14/14、设备 22/22，后端 617 / 前端 532 用例全绿；真实 MySQL 备份演练、真实 iOS、法务与 GitHub 安全设置仍未完成 |

| [2026-11-24-amap-degradation-alert-drill.md](2026-11-24-amap-degradation-alert-drill.md) | 2026-11-24 | 高德降级告警实跑：Prometheus 规则在持续连接失败下的 pending → firing → 恢复 | `TravelExternalProviderDegraded{provider="amap"}` 与 `TravelExternalDegradedHigh` 均真实 firing 并恢复；1 请求 = 1 次降级（1:1）；首个 firing 延迟 ≈16 分钟；真实高德断供/Key 失效与值班路由仍未覆盖 |
| [2026-11-24-infra-mysql84-cold-start.md](2026-11-24-infra-mysql84-cold-start.md) | 2026-11-24 | infra compose 的 mysql:8.4 新卷冷启动 + Flyway 全量迁移与 Hibernate validate | 8.4.13 冷启动成功，23 条迁移应用到 v24、1 test / 0 failures；8.0 旧卷就地升级未演练 |
| [2026-11-25-codeql-triage.md](2026-11-25-codeql-triage.md) | 2026-11-25 | 67 条 CodeQL 告警分诊：高危/中危逐条读代码，ReDoS 用 JVM 探针实测 | 23 条修复（PR #29）、6 条带理由驳回、38 条质量 backlog；ReDoS 最坏 19.2s → 8.4ms |

新增快照后请同步本表，并运行 `node scripts/docs-check.mjs` 校验链接。完整文档规则见 [文档生命周期](../guide/documentation.md)。