# Pull Request

## 1. 变更说明

<!-- 一句话说清“改了什么、为什么改”。关联 issue：Closes #___ -->

- 变更类型：`feature` / `fix` / `docs` / `refactor` / `chore` / `security` / `deps`（保留一个）
- 关联任务 / issue：
- 影响范围（页面、接口、表、配置、外部依赖）：

## 2. 风险与影响

<!-- 没有风险也要写“无”，不要留空 -->

- 数据与迁移：是否新增 Flyway `V{n}__`？是否 forward-only？是否已在真实 MySQL 8.4 跑过？
- 配置：新增/修改了哪些环境变量？是否已同步 `docs/operations/production-config.md` 与 `.env.production.example`？
- 外部依赖：LLM / 高德 / MCP / S3 / Redis 的失败路径是什么？降级后用户看到什么？
- 安全与隐私：是否涉及日志、密钥、Cookie、票据、用户数据删除/导出？
- 兼容性：是否破坏 API（oasdiff）、数据库旧版本、旧前端产物？

## 3. 回滚方案

<!-- 必须可执行：回滚哪个 commit/镜像？数据库是否可回滚？数据是否需要恢复备份？ -->

- 代码回滚：
- 数据库迁移回滚（Flyway 无 down，说明是否只能恢复备份）：
- 配置回滚：
- 回滚后如何验证：

## 4. 测试证据

- [ ] 后端：`cd travel-agent-server && mvn -B clean verify`
- [ ] 前端：typecheck / lint / format / coverage / build budget / API 契约（按 `docs/guide/quality-gates.md`）
- [ ] 文档：`node scripts/docs-check.mjs`
- [ ] MySQL 迁移（如涉及）：真实 MySQL 8.4 + `ddl-auto=validate` 通过
- [ ] E2E / 视觉回归（如涉及）：
- CI 链接或本地输出摘要：

```text
<粘贴关键输出：测试数量、失败数、构建结果；不要粘贴密钥/Token>
```

## 5. 截图 / 录屏（UI 变更必填）

<!-- 桌面与移动端各至少一张；标注视口尺寸（320/390/768/1024/1440）。 -->

- 变更前：
- 变更后：
- 失败 / 空 / 加载状态（如涉及）：

## 6. 移动端与真机声明（必须保留）

> **Playwright WebKit 是模拟 Safari 引擎，不等于真实 iOS Safari；真机结论必须附设备/iOS/Safari 版本与截图。**

- [ ] 本 PR 的移动端结论仅来自 Playwright WebKit / Chromium 模拟，**不是**真机结论。
- [ ] 若声称“真实 iOS Safari 通过”，已附：设备型号、iOS 版本、Safari 版本、网络条件、关键路径截图。
- [ ] 若未做真机验证，已在此明确写“未做真机验证”，没有把模拟结果写成真机结论。

## 7. 检查清单

- [ ] 没有提交真实密钥、`.env`、证书、用户数据或含密钥的日志/截图。
- [ ] 新增/修改的日志不含 JWT、Cookie、API Key、票据、手机号等敏感信息。
- [ ] 涉及用户数据删除/导出时，已同步 `docs/legal/data-deletion.md` 并验证残留为 0。
- [ ] 涉及许可证/第三方依赖时，已同步 `docs/legal/licensing.md` 与 `docs/legal/third-party-services.md`。
- [ ] 文档与代码一致；新增文档已挂到 `docs/guide/README.md` 或 `docs/operations/README.md`。
- [ ] 已确认不需要改 `ci.yml` / `codeql.yml`；若确实需要，单独说明原因并请 CI 维护方评审。