# 评审与验收快照（Review Snapshots）

> 状态：目录规范 | 适用版本：0.1.0（Spring Boot 3.5.7 / Node 20） | 最后验证：2026-11-19 | 事实来源：docs/review/、docs/guide/documentation.md

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

新增快照后请同步本表，并运行 `node scripts/docs-check.mjs` 校验链接。完整文档规则见 [文档生命周期](../guide/documentation.md)。