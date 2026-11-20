# 方案契约与提案（Proposals）

> 状态：目录规范 | 适用版本：0.1.0（Spring Boot 3.5.7 / Node 20） | 最后验证：2026-11-19 | 事实来源：docs/proposals/、docs/CHANGELOG.md、docs/guide/

本目录保存**方案契约与提案**：并行开发前约定的共享契约（batch*），以及尚未排期的改进提案（如 TREK-BENCHMARK）。它们记录「当时约定了什么」，**不是当前版本的行为保证**；当前事实一律以 [docs/guide](../guide/README.md) 为准。

## 1. 状态口径

| 状态 | 含义 | 处理方式 |
| --- | --- | --- |
| 未落地 | 提案 / 契约尚未排期或只完成部分 | 保留原文；在文件头标「状态：未落地」与日期 |
| 已落地（历史） | 契约内容已进入实现，作为历史契约追溯 | 保留原文不删除；标「状态：已落地（历史）」；落地证据链到 CHANGELOG / guide |
| 已废弃 | 方案被后续决策取代，不再实施 | 保留原文；标「状态：已废弃」并写明取代它的文档 |

规则：

- **不删除历史提案**：状态变化只改文件头标注，正文保留原貌，便于追溯当时的约束与取舍。
- 已落地契约**不回写成「当前事实」**；当前实现以 `docs/guide/` 与源码为准。
- 新提案必须写清：背景、范围、验收标准、不做什么、依赖与风险，以及状态与日期。
- 提案落地后：补「状态：已落地（历史）」、在 [CHANGELOG](../CHANGELOG.md) 记录批次，并把长期有效的事实沉淀进 `docs/guide/`。
- 外部事实（第三方能力、价格、授权）标「待确认」，不要写成承诺。

## 2. 头部模板

```markdown
# 标题

> 状态：未落地 / 已落地（历史）/ 已废弃 | 日期：YYYY-MM-DD | 说明：一句话（已落地写落地批次，已废弃写取代文档）
```

## 3. 状态表

| 文件 | 状态 | 日期 | 摘要 | 落地证据 |
| --- | --- | --- | --- | --- |
| [batch9-contract.md](batch9-contract.md) | 已落地（历史） | 2026-11-16 | 安全 / 配额、分页与聚合、知识库版本化、城市匹配、会话记忆、前端 | [CHANGELOG 第九批](../CHANGELOG.md) |
| [batch10-contract.md](batch10-contract.md) | 已落地（历史） | 2026-11-16 | 真实数据源（MCP 高德）、schema 单一权威、知识库不变量、可观测性、暗色 / PWA | [CHANGELOG 第十批](../CHANGELOG.md) |
| [batch13-contract.md](batch13-contract.md) | 已落地（历史） | 2026-11-18 | FR-06 审核改变结果 + FR-07 路线段进时间线 | [CHANGELOG 第十三批](../CHANGELOG.md) |
| [batch14-contract.md](batch14-contract.md) | 已落地（历史） | 2026-11-18 | FR-01 需求槽位与最少追问、人数贯通预算 | [CHANGELOG 第十四批](../CHANGELOG.md) |
| [batch15-contract.md](batch15-contract.md) | 已落地（历史） | 2026-11-18 | FR-02 / FR-04 任务 DAG 与委派 | [CHANGELOG 第十五批](../CHANGELOG.md) |
| [TREK-BENCHMARK.md](TREK-BENCHMARK.md) | 未落地 | 2026-11-16 | 对标 TREK 的结构性缺口分析与改进提案 | 未排期 |

新增提案或状态变化后请同步本表，并运行 `node scripts/docs-check.mjs` 校验链接。完整文档规则见 [文档生命周期](../guide/documentation.md)。