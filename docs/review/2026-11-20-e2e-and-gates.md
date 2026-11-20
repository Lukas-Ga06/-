# 本次收尾验收：门禁实测 + e2e 实跑 + 口径修正（2026-11-20）

> 快照日期：2026-11-20 ｜ 验证范围：后端 `mvn -B verify`、前端四道门禁、Playwright e2e 8 条、回顾口径 ｜ 环境：Windows / JDK 17.0.1 / Maven 3.9.16 / Node 20.17.0 / Playwright 1.64.1 ｜ 结论：**本地门禁全绿、e2e 8/8**；远端 CI、真机与真实模型仍未验

## 1. 后端（`cd travel-agent-server && mvn -B verify`）

| 项 | 实测 | 阈值 | 结论 |
| --- | --- | --- | --- |
| 单元/集成测试（H2） | **540 tests / 0 failures / 0 errors / 1 skipped** | — | PASS |
| 唯一 skip | `migration.MySqlFlywayMigrationTest`（无 Docker/MySQL 按设计跳过） | — | 预期 |
| JaCoCo INSTRUCTION | **79.73%**（38038/47707） | ≥ 0.76 | PASS |
| JaCoCo BRANCH | **60.81%**（3105/5106） | ≥ 0.57 | PASS |
| JaCoCo LINE | **79.15%**（7252/9162） | ≥ 0.76 | PASS |
| Checkstyle | **0 violations**（228 个主代码文件） | 0 | PASS |
| SpotBugs | **0 findings / 0 errors**（421 个类） | 0 | PASS |

## 2. 前端（`cd travel-agent-web`）

| 命令 | 结果 |
| --- | --- |
| `npm run typecheck` | 0 error |
| `npm test` | **44 文件 / 445 用例全过** |
| `npm run test:coverage` + `check-core-coverage` | 全局 56.88/55.61/51.6/57.65；核心目录 79.24/76.76/68.97/78.23，阈值达标 |
| `npm run lint` | 0 error（48 条历史 warning） |
| `npm run format:check` | 通过 |
| `npm run build:check` | 构建成功；最大 JS 572,028 B、首屏 572,162 B、地图引擎不进 precache、precache 2.08 MiB，预算全过 |
| `npm run api:check` | 契约类型无漂移 |

## 3. e2e（真实后端 + Chromium）

- 命令：`E2E_API_TARGET=http://localhost:8081 npx playwright test --reporter=list`
  （后端 H2 独立库、`RATE_LIMIT_ENABLED=false`；前端 `vite preview` 4173）。
- 结果：**8/8 PASS，30.2s**。明细与首轮 6/8 的原因见
  [`.e2e/acceptance/evidence/e2e-20261120-summary.md`](../../.e2e/acceptance/evidence/e2e-20261120-summary.md)。
- 首轮失败是断言过期（路书按 P1-05 收进「更多行程操作」菜单），修正断言后全绿。

## 4. 本轮口径修正

- `/review` 不再把待出发行程当作可回顾内容（设计文档 §18 第 9 条）：可回顾列表只含进行中/已结束/已取消；
  待出发单列「待出发（尚不可回顾）」，只给「行程」入口。`src/pages/Reviews` 与测试同步。
- [quality-gates.md](../guide/quality-gates.md) 的数字从 525/79.72%/79.20%/60.66% 更新为实测值；
  [PRODUCT_DEVELOPMENT.md](../PRODUCT_DEVELOPMENT.md) 与
  [external-dependencies.md](../guide/external-dependencies.md) 的「461 用例基线」同步为 540。

## 5. 仍待办（不在本快照的「已通过」范围内）

- GitHub Actions 全部 job（本机无 remote，编排从未远端实跑）；`mysql-migration` job 同理。
- 真实 MySQL 8.4 迁移：最近一次实机证据为 2026-11-19（`scripts/mysql-it.ps1` + `.evidence/mysql84-*`）。
- 真实模型、真实 Embedding 质量、真实 OTA 库存、iOS/Safari 真机、弱网与多端并发确认。
- 设计文档阶段 C 的 AI 助手部分与阶段 E（视觉系统、次级页面、无障碍与视觉回归）。