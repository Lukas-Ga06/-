# 阶段 C 收口验收：AI 助手四项 + 会话隔离（2026-11-20 深夜）

> 快照日期：2026-11-20 ｜ 验证范围：结构化结果卡、会话作用域隔离、停止生成、结果后可点击建议、过程折叠 ｜ 环境：Windows / Node 20.17.0 / Playwright 1.64.1（Chromium）/ 本地 H2 后端 ｜ 结论：**四项落地，前端 465 用例与 e2e 8/8 全绿**；最近会话列表与阶段 E 未做

## 1. 交付内容

| 能力 | 实现 | 证据 |
| --- | --- | --- |
| 结构化结果卡 | `components/ResultCard`：状态（已完成/待确认/部分完成/已停止/失败）、意图、影响范围（Diff + 预算前后）、审核问题、待核实、来源与主操作 | 8 条组件测试；`result-card.css` |
| 会话隔离 | `agentStore` 增加 `scope` / `conversations` / `setScope`；`hooks/useAgentScope` 由页面在挂载时切换；助手 / 规划器 / 每个行程各一作用域 | 3 条 store 用例（消息隔离、会话标识隔离、切换丢弃在途流） |
| 停止生成 | 保留既有 abort；停止后写 `CANCELLED` 结果卡并给「重试」，输入立即恢复；服务端将 run 标为 CANCELLED | 2 条 store 用例 + 1 条 ChatPanel 用例 |
| 可点击建议 | 空态 4 个示例保留；结果后按状态给 2–3 个建议（待确认 / 已完成 / 失败与停止） | 3 条 ChatPanel 用例 |
| 过程折叠 | 默认折叠，只露最新一条用户语言进度；DAG 与工具调用收进「查看运行详情」 | AgentActivityPanel 与 ChatPanel 用例 |

## 2. 本轮发现并修复的真实回归

首轮 e2e **6/8**：两条规划链路停在「方案确认弹窗不出现」。

- 根因：规划器是「先提交表单、后渲染 ChatPanel」，最初把作用域切换放在 ChatPanel 挂载时，
  切作用域会自增流序号并关闭连接，**把页面刚发起的在途流当旧流取消**。
- 修复：作用域切换改为页面级 `useAgentScope`（Planner / Trip / Assistant 挂载即切），并移除
  规划器与行程页会清掉「上一个页面作用域」消息的 `reset()`。
- 复跑 e2e：**8/8 PASS（31.1s）**。

## 3. 门禁实测

- `npm test`：**45 文件 / 465 用例全过**。
- `npm run test:coverage`：全局 57.51/56.87/52.33/58.28；store/api/lib 79.67/77.04/69.32/78.66，阈值全过。
- `npm run typecheck` 0 error；`npm run lint` 0 error（48 条历史 warning）；`npm run format:check` 通过。
- `npm run build:check`：包体预算全过（首屏 572 kB 上限内、地图引擎不进 precache）。
- `npm run api:check`：契约无漂移。
- Playwright e2e 8/8（`.e2e/e2e-run-stagec2.log`）。

## 4. 仍待办

- §8.6「最近会话列表，支持新建对话和继续对话」：缺后端会话列表接口，本轮未做。
- 阶段 D 的冲突提示（「此行程已在其他位置更新」）。
- 阶段 E 全部（视觉系统、次级页面、无障碍与视觉回归）。
- 真实环境验收：MySQL 实机、远端 CI、真实模型、iOS/Safari 真机、弱网与多端并发。