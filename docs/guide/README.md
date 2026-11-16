# AI 个人助理 · 项目文档

以「行前规划 + 行中动态调整」为主线的个人助理系统，覆盖旅行、记账、备忘、日程、办公自动化、学习辅助与健康数据。
本目录是面向**开发者、集成者与运维**的完整文档套件；项目定位与功能全貌请先看根目录 [README.md](../../README.md)。

---

## 文档地图

按「我现在的角色」选入口：

| 角色 / 目标 | 推荐阅读顺序 |
| --- | --- |
| **新加入的开发者** | [快速上手](getting-started.md) → [架构](architecture.md) → [数据模型](data-model.md) → [开发约定](../DEVELOPMENT.md) |
| **前端开发者** | [快速上手](getting-started.md) → [前端结构](frontend.md) → [API 参考](api-reference.md) |
| **后端 / Agent 能力开发** | [架构](architecture.md) → [API 参考](api-reference.md) → [数据模型](data-model.md) → [开发约定](../DEVELOPMENT.md) |
| **第三方集成 / 对接接口** | [API 参考](api-reference.md)（含认证、票据、错误码） |
| **部署与运维** | [部署与运维](deployment.md) → [快速上手](getting-started.md) 第 2 节 |
| **想知道「为什么改成这样」** | [CHANGELOG](../CHANGELOG.md) → [开发约定](../DEVELOPMENT.md) 末尾的加固批次说明 |

---

## 篇目索引

| 文档 | 内容概要 | 适合谁 |
| --- | --- | --- |
| [getting-started.md](getting-started.md) | 环境要求、`run-dev.ps1` 启动、首次启动自动完成的事、前端启动与 `/api` 反代、演示闭环 walkthrough、常见问题排查表、提交前自检清单 | 所有人（第一天读） |
| [architecture.md](architecture.md) | 分层架构、8 状态机、Intent/Planner/Executor/Reflect 四 Agent、AgentPlan 与 PlanOperation、三级风险 Human-in-the-loop、工具层只读闸门与 8 个内置工具、RAG 与 Embedding 降级、SSE 事件协议与 focus、会话记忆、待确认方案落库、MCP 生态、LLM 配额 | 后端 / Agent 能力开发 |
| [api-reference.md](api-reference.md) | 通用约定（认证、短期票据、统一响应、错误码）、按业务模块逐接口的真实契约（参数 / 请求体 / 响应 / 错误码 / 源码位置）、接口总表 | 前端 / 第三方集成 |
| [data-model.md](data-model.md) | ER 总览、全部实体与表结构（字段 / 类型 / 索引 / 约束）、Flyway 迁移策略、数据一致性不变量、引导数据、常用 SQL 手册 | 后端 / 数据 |
| [frontend.md](frontend.md) | 技术栈、目录结构、路由表、页面清单、zustand 状态管理、API 层与 SSE 消费链路、核心组件、高德地图 Provider 抽象、构建与测试 | 前端 |
| [deployment.md](deployment.md) | 部署拓扑、Docker 全栈部署步骤、**生产必改项清单**、环境变量权威表、H2/MySQL 切换、卷与备份恢复、反代与 HTTPS、监控与排障、CI 与发布 | 运维 |
| [mcp-amap.md](mcp-amap.md) | 高德 MCP：包身份与两个 Key 的区别、开启步骤、12 个工具与 `mcp_amap_*` 注册名、语义适配层、排障与降级、已验证/未验证边界 | 后端 / 运维 |

补充材料：

| 文档 | 内容 |
| --- | --- |
| [../DEVELOPMENT.md](../DEVELOPMENT.md) | 开发与配置指南：环境变量、测试约定、关键不变量（错误语义、工具层只读、事务边界、撤销语义、日期映射、RAG 过滤、记账对账、预算口径、确认版本、意图路由、会话记忆、URL 凭证、照片安全…）、设计系统 |
| [../CHANGELOG.md](../CHANGELOG.md) | 行为变化记录（含影响使用习惯的调整） |
| [../../README.md](../../README.md) | 项目定位、功能全貌、快速启动、核心设计概要、API 速查、后续规划 |
| [../design/preview/](../design/preview/) | 静态设计预览（`index.html` + `preview.css`），用于对齐视觉方向 |

---

## 一分钟建立心智模型

```
自然语言 → Intent（意图）→ Plan（操作列表，不落库）→ Preview（Diff + 风险分级）
        → 用户确认 → Commit（单事务：Service → Repository + 操作历史）→ 可撤销
```

三条不变量决定了整个系统的形态：

1. **工具层只读**：`ToolRegistry` 拒绝注册任何写操作工具（`readOnly()==false`）。
   任何「会改数据」的能力都必须表达为 `AgentPlan` 的操作项，经用户确认后才落地——
   因此不存在「工具绕过确认直接改库」的路径。
2. **写入只有一个入口**：`ExecutorAgent.commit` 是唯一的落库入口且事务化，行程变更与操作历史同生共死。
3. **确认前可失效**：待确认方案记录生成时的行程指纹（日期 + 预算 + 全部条目），确认时若与当前行程不一致
   则方案失效并要求重新生成，避免多标签页按过期 Diff 覆盖新数据。

细节、代码位置与边界条件见 [architecture.md](architecture.md)。

---

## 模块速览

### 后端（`travel-agent-server`，Spring Boot 3 / Java 17，184 个主代码文件）

| 包 | 文件数 | 职责 |
| --- | --- | --- |
| `agent` | 16 | Agent 引擎：状态机、四个 Agent、上下文、计划、会话记忆、待确认方案 |
| `tool` | 5 | `AgentTool` 契约、`ToolRegistry` 只读闸门、`SafetyInterceptor` 风险分级、`ToolInvoker` |
| `rag` | 10 | Embedding（本地哈希 / OpenAI 兼容）、内存向量库、RAG 检索、知识库引导 |
| `llm` | 6 | 统一 LLM 接口（OpenAI-compatible，可切 DeepSeek/Qwen）+ 规则实现 + 委派与降级 |
| `mcp` | 6 | MCP 外部工具桥接（streamable HTTP / 本机 stdio） |
| `controller` | 15 | REST API + SSE 流式接口 |
| `service` | 31 | 行程 / 预算 / 操作历史 / 目录 / 天气 / 路线 / 办公 / 学习 / 健康 / 提醒 / 路书 … |
| `entity` / `repository` | 19 + 19 | JPA 实体与数据访问 |
| `security` / `config` | 9 + 10 | JWT、Cookie、访问票据、限流、安全配置、CORS、时区、TraceId |
| `channel` | 7 | 多渠道入口：Telegram / 微信公众号 / 通用 Webhook |
| `dto` / `model` / `util` / `exception` | 10 / 6 / 4 / 2 | 传输对象、领域模型、通用工具、全局异常 |

### 前端（`travel-agent-web`，React 18 + TypeScript + Vite，86 个源文件）

| 目录 | 文件数 | 职责 |
| --- | --- | --- |
| `pages` | 18 | 16 个页面目录（各含 `index.tsx`）+ 规划表单逻辑 `planForm.ts` 与其测试 |
| `components` | 34 | ChatPanel / MapView(高德 2D/3D) / Timeline / ItineraryCard / PlanDiff / BudgetChart / FootprintMap / ReminderBell … |
| `api` | 14 | axios 封装、SSE 客户端、票据、各业务接口（含测试） |
| `store` | 7 | zustand：auth / trip / agent / ui / theme（含 `agentStore.test.ts`） |
| `types` / `lib` / `hooks` / `styles` / `test` | 5 / 2 / 2 / 1 / 1 | 与后端对齐的 TS 类型、`reminderNotify`（浏览器通知）、`useIntersection` / `useOnlineStatus`、`global.css` 样式令牌、vitest 环境 `setup.ts` |

---

## 文档维护约定

- 本目录所有文档以**源码为准**：与代码不一致时以代码为准，并在 PR 中同步修订文档；
- 新增接口 / 表 / 页面时，请同步更新 `api-reference.md` / `data-model.md` / `frontend.md` 的对应章节与文末统计；
- 涉及使用习惯变化（默认行为、错误码、环境变量语义）的改动，请同时写入 [CHANGELOG](../CHANGELOG.md)；
- 文档中出现的类名、方法名、环境变量名必须可在仓库中检索到，禁止臆造。
