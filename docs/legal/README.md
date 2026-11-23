# 合规与法务文档索引

> 状态：**草案，发布前需法务确认** | 最后更新：2026-11-22 | 事实来源：`docs/legal/`、`LICENSE`、代码中的数据模型与接口

本目录放发布前必须定稿的合规文档。当前所有文档都按**代码现状**编写，不声称已经满足任何特定法域（GDPR/PIPL 等）的合规要求；占位符必须由运营主体与法务替换。

## 文档清单

| 文档 | 内容 | 发布前状态 |
| --- | --- | --- |
| [privacy-policy.md](privacy-policy.md) | 收集的数据类别、用途、保留期、第三方处理方、用户权利、联系方式占位 | **草案**；需替换 `[运营主体]`/`[隐私联系邮箱]`/`[生效日期]` 并法务确认 |
| [third-party-services.md](third-party-services.md) | LLM / Embedding / 高德 / Open-Meteo / S3 / Redis / 渠道 / GitHub 的出站数据、密钥归属、降级行为 | **待逐项确认合同、地域、保留期** |
| [data-deletion.md](data-deletion.md) | 账号/行程/照片/会话/派生数据的删除流程、`DELETE /api/auth/me` 现状与语义、运维手工 SQL 与验证 | **账号注销已实现（H2 已验证；MySQL/S3 未实测）**；账号级导出仍未实现；手工 SQL 为兜底 |
| [licensing.md](licensing.md) | 专有许可现状、为什么未选 MIT/Apache、切换开源许可证的流程与第三方审计要求 | **专有许可已声明**；开源切换是业务决策 |
| [`../../LICENSE`](../../LICENSE) | 专有版权声明（All rights reserved，未授予开源许可） | 已添加 |
| [`../../README.md`](../../README.md) | 根 README 的“状态与许可证”段落指向本目录 | 已同步 |

## 发布前必须闭环（P0-D）

- [ ] 运营主体、联系方式、生效日期、政策版本全部替换为真实值。
- [ ] 法务确认适用法域、跨境机制、告知/同意方式与留存期。
- [ ] 账号注销与账号级数据导出均已完成 H2 验收；继续完成 MySQL/S3 实机、内存 RAG 与旧 JWT 同名重注册风险处置（见 [data-deletion.md](data-deletion.md)）。
- [ ] 第三方处理方的合同/DPA、数据地域与保留期逐项确认。
- [ ] 日志保留期与轮转、nginx 票据日志问题收口（见 [Runbook 附录 A](../operations/runbook.md)）。
- [ ] 许可证策略由业务/法务书面确认；若开源，完成依赖许可证审计与 NOTICE。
- [ ] 发布审批记录包含安全、隐私、备份、依赖扫描四个签字点。

相关入口：[运维与合规索引](../operations/README.md) · [生产配置清单](../operations/production-config.md) · [项目路线图](../PROJECT_ROADMAP.md)