# 会话 scope 验收：跨页面续聊不再丢失行程绑定（2026-11-21）

> 快照日期：2026-11-21 ｜ 验证范围：`conversation_message.scope` 迁移（V21）、请求体/查询参数 `scope` 字段、会话列表与续聊回填返回 scope、前端恢复行程绑定 ｜ 环境：Windows / JDK 17.0.1 / Maven 3.9.16 / Node 20.17.0 / MySQL 8.4.10（:3307 便携实例）/ H2（单测） ｜ 结论：**scope 端到端打通，跨页面续聊恢复 tripId；后端 550 用例、迁移链 V1..V21 在真实 MySQL 上全绿**；会话管理（重命名/删除）仍未做

## 1. 上一版留下的具体缺陷

§8.6 的验收快照（[2026-11-21-recent-conversations.md](2026-11-21-recent-conversations.md)）在「边界与仍待办」里写死了一条：

> **没有 scope 字段**：conversationId 由前端随机生成，服务端不记录它属于「助手」还是「行程 12」。
> 因此列表会混含各页面的会话；在助手页继续一段行程会话时，**只恢复消息，不恢复行程绑定**（下一条消息不带 `tripId`）。

前端其实**已经有**作用域概念（`agentStore.scope`：`assistant` / `planner` / `trip:<id>`，阶段 C 做的会话隔离），
它只活在浏览器内存里 —— 服务端对此一无所知。所以：

- 列表把三个页面的对话混在一起，用户看不出哪段是在哪个行程里聊的；
- 抽屉里点「继续」只把消息装回来，`tripId` 不会跟着回来，下一句「把第二天下午的景点删一个」
  就丢掉了「在哪个行程里」这个前提。

## 2. 这一轮做了什么

### 2.1 存储（V21 迁移）

`V21__conversation_scope.sql`：

```sql
ALTER TABLE conversation_message ADD COLUMN scope VARCHAR(32);
CREATE INDEX idx_conv_user_scope ON conversation_message (user_id, scope);
```

- `scope` 取值约定 `assistant` / `planner` / `trip:<id>`，列宽 32 与 DTO 校验一致。
- **存量行一律 NULL，不猜**。从聊天正文反推「这句话是不是在行程 12 里说的」属于编造数据；
  NULL 在契约里显式表示「未绑定」，前端按本次改造前的行为处理（不恢复任何绑定）。
- 不设唯一约束、不做外键：同一行程可被多段会话绑定，行程被删也不该让历史变得不可读。
- 双方言中性 SQL（H2 `MODE=MySQL` 与 MySQL 8.0/8.4 都能执行），索引名与实体 `@Index` 一致。

### 2.2 契约（三个字段 + 一个响应体变形）

| 位置 | 变更 | 兼容性 |
| --- | --- | --- |
| `ChatRequest.scope` / `PlanStreamRequest.scope` | 新增，可选，≤32 字符 | 旧客户端不传 → 走推导（见 2.3） |
| `GET /api/agent/conversations` 的 `ConversationSummary` | 新增 `scope` 字段 | 纯新增，旧前端忽略 |
| `GET /api/agent/conversations/{id}/messages` | 响应体从**裸数组**改为 `ConversationMessagesView{conversationId, scope, messages}` | **不兼容**：只有本仓库前端调用，已同步改造；见下 |

回填接口的变形是必要的：续聊要恢复行程绑定，就必须在同一响应里拿到作用域。分成两个接口
（列表查 scope + 消息查内容）会引入「两次请求之间作用域又变了」的竞态，也让抽屉多一次往返。
`ConversationMessagesView` 是新 schema，已加入 `api:check` 的核心契约清单。

### 2.3 作用域从哪来：声明优先，其次推导，绝不猜测

`AgentController.effectiveScope(request)`：

1. 客户端显式声明且合法（`[A-Za-z0-9:_-]+`、≤32）→ 用它；
2. 否则按**请求自身已有的上下文**推导：带 `tripId` → `trip:<id>`，不带 → `assistant`；
3. 非法值（含空格、`/`、`..`）按「未声明」处理，走第 2 步 —— **不返回 400**。
   作用域是展示与续聊的提示，不该有否决一条消息的权力。

推导只看请求字段，绝不查库、不读正文。老客户端因此也能得到正确的分组，而不是全部堆在「未绑定」。

### 2.4 读路径：列表与回填取「最近一次」

- 列表：`group by conversationId` 里 `coalesce(max(scope), '')`，Java 侧把空串还原成 `null`
  （「未绑定」只有一个表示法）。同一会话所有行 scope 相同，`max()` 与「最后一条」等价且不必加子查询。
- 回填：把查询结果反转成时间正序后取**最后一条**的 scope —— 与列表同一口径，
  也就是「这段会话最近一次被用在哪个页面」。

### 2.5 前端：续聊把绑定装回去

- `agentStore` 新增 `resumedTripId`；`resumeConversation(conversationId, messages, conversationScope)`
  用 `tripIdOfScope()` 解析 `trip:<id>`（只认正整数，`trip:0` / `trip:-1` / `trip:abc` 一律 null）。
- `send()`：`payload.tripId == null` 时用 `resumedTripId` 兜底；**页面自己带了 tripId 以页面为准**
  —— 用户此刻就在那个行程里，比历史绑定更可信。
- `resumedTripId` 在 `setScope` / `reset` / `newConversation` 时清空：绑定属于上一段对话，不能泄漏到别的页面。
- 抽屉：列表行显示会话来源标签（`行程 #12` / `规划` / `助手`），未绑定的存量会话**不显示任何标签**；
  续聊时以回填返回的 scope 为准（它比列表副本新：期间可能在别的标签页聊过）。

## 3. 验证结果

### 3.1 真实 MySQL 8.4.10 迁移（V1..V21）

```
Successfully applied 20 migrations to schema `travel_agent_it_20261121201924`,
now at version v21 (execution time 00:04.312s)
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0 -- MySqlFlywayMigrationTest
[INFO] BUILD SUCCESS
```

- 命令：`powershell -NoProfile -ExecutionPolicy Bypass -File scripts\mysql-it.ps1`（便携实例 `127.0.0.1:3307`）
- 迁移链：V1–V13（V8/V12/V13 为 MySQL 方言版）+ V15–V21，共 20 个，无 V14（H2 专属）；
  `V21 - conversation scope` 成功应用，Hibernate `ddl-auto=validate` 通过。
- 证据：`.evidence/mysql-v21-fix/`（`mysql-it.log`、surefire 报告、`SUMMARY.md`）。

**过程中发现并修掉一个真实漂移**：首轮 FAIL，断言信息是

```
[Hibernate 元模型实体数] Expected size: 19 but was: 20 in: [..., "AgentCommandDedupe", ...]
```

`MAPPED_ENTITIES` / MAPPED_TABLES 与 `hasSize(19)` 是手写清单，V20 引入 `AgentCommandDedupe` 实体时漏更新，
只是此前没人跑过真实 MySQL（该测试默认被 `MYSQL_IT=true` 跳过）。本轮已补齐为 20 个实体，
并把 `hasSize(19)` 改成 `hasSize(MAPPED_ENTITIES.size())` —— 数量与清单不可能再各说各话。

### 3.2 后端 `mvn verify`

```
[INFO] You have 0 Checkstyle violations.
[INFO] BugInstance size is 0
[WARNING] Tests run: 550, Failures: 0, Errors: 0, Skipped: 1
[INFO] BUILD SUCCESS
```

550 用例（改造前 HEAD 实测 546，净增 4）；JaCoCo / Checkstyle / SpotBugs 全过。
证据：`.evidence/mvn-verify-scope.log`。

新增 5 个用例（`ConversationListApiTest`）：

| 用例 | 断言什么 |
| --- | --- |
| `summariesCarryScopeAndLegacyRowsStayNull` | 列表回带 `trip:12` / `planner`；存量行是**显式 null**，不回落成猜测值 |
| `resumePayloadCarriesLatestScope` | 回填作用域取「最近一次使用」而非「第一次」 |
| `chatPersistsDeclaredOrDerivedScope` | 声明值落库；不传 scope 时按 `tripId` 推导 |
| `invalidScopeDegradesToDerived` | 脏 scope 不 400、不丢消息，按未声明走推导 |
| （含既有 9 例改造） | 回填响应结构从数组改对象后，隔离与「最近 N 条」语义不变 |

### 3.3 真实 HTTP 冒烟（不只是 MockMvc）

用真实后端（`spring-boot:run`、demo 账号、H2 文件库）走了一遍三条路径：

| 场景 | 请求 | 结果 |
| --- | --- | --- |
| 声明 scope | `POST /api/agent/chat {conversationId, scope:"trip:42"}` | 列表返回 `scope: trip:42`；回填返回 `scope: trip:42` 与 2 条消息（user → assistant） |
| 未声明 scope | `POST /api/agent/chat {conversationId, tripId:9}` | 列表返回 `scope: trip:9`（按请求里的 tripId 推导） |
| 非法 scope | `POST /api/agent/chat {conversationId, scope:"trip 1/../etc"}` | **不 400**；列表返回 `scope: assistant`，消息照常落库 |

证据：`.evidence/scope-http-smoke/RESULT.md`（含原始响应片段与清理记录）。

### 3.4 前端

- `agentStore.test.ts` **44 例**（新增 5）：send 带 scope、续聊恢复 tripId、页面 tripId 优先、
  未绑定不回填、切换作用域/新会话后失效、`tripIdOfScope` 边界。
- `ConversationDrawer/index.test.tsx` **9 例**（新增 3）：来源标签、续聊恢复绑定、回填 scope 优先于列表副本。
- `api/agent.test.ts` **11 例**（新增 2）：scope 进查询参数；无 scope 时 URL 里不出现空参数。
- `npm run typecheck` / `api:check`（paths=54, schemas=103，无漂移）通过。

## 4. 边界与仍待办

- **未绑定 ≠ 助手页**：V21 之前的存量会话 scope 为 NULL，不会出现在任何「按作用域分组」的视图里。
  这是有意的取舍（不伪造归属），代价是老会话在列表里没有来源标签。
- **作用域不是权限**：它由客户端声明、服务端清洗后记录，`trip:12` 不代表「有权访问行程 12」。
  真正的归属校验仍在各业务接口（行程/日程/账单）里按 `userId` 做。**这一点必须保持**，
  否则 scope 会变成一条越权旁路。
- **会话管理未做**：重命名、删除单段会话（现在只能「新建对话」清掉当前会话）。
- **真实多端并发未验**：两个标签页各自持有独立 `conversationId`（`sessionStorage` 隔离），
  因此不会出现「两个端写同一段会话」的情况；但同一段会话被显式续聊到两个端时，服务端
  `append` 没有任何并发控制（无唯一约束、无版本号），消息会按到达顺序交错追加。
  这是真实缺口，记录在 [真机与弱网验收](2026-11-21-device-network-acceptance.md) 的并发一节。