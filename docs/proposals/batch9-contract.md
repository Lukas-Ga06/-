# 第九批共享契约（Batch 9 shared contract）

> 状态：已落地（历史） | 日期：2026-11-16 | 说明：契约内容已进入实现，保留原文作追溯；当前事实见 [docs/guide](../guide/README.md)，落地记录见 [CHANGELOG 第九批](../CHANGELOG.md)。

本文件是本批并行开发的**唯一约定源**。任何与本文件冲突的实现都算错。
工作流代号：A（安全/配额）、B（分页与聚合）、C（知识库版本化）、D（城市匹配）、E（会话记忆）、F（前端）。

## 0. 通用规则

- 只改自己任务卡里的 `write_scopes`；跨范围需要的签名改动，**先在交付说明里提出**，不要直接改别人的文件。
- 不 `git commit`、不 `git checkout`/`stash`/`restore`、不 `git add`（本仓库有大量未提交在途改动，回滚会毁掉别人的工作）。
- 不启动常驻服务（`spring-boot:run`、`npm run dev`、`npm run preview` 一律不用）。
- 迁移号唯一（V8=A、V9=B、V10=C）。**不要修改 V2–V7 存量脚本**，纠错一律用新脚本。
- 不在 `application.yml` 里加新键（该文件由集成方统一改）。配置项用 `@Value("${...:默认值}")` 或 `@ConfigurationProperties` 的 Java 默认值。

### 构建与测试串行化（重要）

`travel-agent-server/target/` 是共享目录，6 个 agent 同时 `mvn` 会互相踩。**所有 maven 命令必须走锁**：

```powershell
# 编译（只验证，不跑测试）
pwsh -File D:\travel-agent\.tools\mvn-lock.ps1 -Args '-o -q -DskipTests test-compile'
# 只跑自己的测试
pwsh -File D:\travel-agent\.tools\mvn-lock.ps1 -Args '-o -q test -Dtest=MyTest -DfailIfNoTests=false'
```

- 锁超时 900s，拿不到锁就重试一次；仍失败则在交付说明里写明"未验证"。
- **编译报错落在你范围之外的文件上（队友半成品）→ 记录并跳过，不要修**；只保证自己范围内文件语法与类型正确。
- 测试数据库是 H2 `MODE=MySQL` + `create-drop`（测试 profile 关 Flyway），所以**新迁移脚本不会被测试执行**，必须自己在交付说明里写清 SQL 语义正确性依据。

## 1. 列表分页响应契约（A/B/C/D/E/F 都不得破坏）

`dto/ApiResponse.java` 增加**可选**分页元数据，JSON 形态向后兼容（老客户端只读 `data` 不受影响）：

```json
{ "code": 0, "message": "success", "data": [ ... ],
  "pagination": { "total": 123, "limit": 20, "offset": 0, "hasMore": true } }
```

> 施工时以仓库现有信封为准：是 `code`（0 = 成功）不是 `success`；前端按 `code` 解包，`pagination` 读顶层键。
> 本契约文件保留作实施记录，最终实现见 `docs/CHANGELOG.md` 第九批与 `docs/guide/api-reference.md` §1.6。

- Java：`ApiResponse<T>` 增加 `Pagination pagination` 字段 + `static <T> ApiResponse<T> ok(T data, Pagination page)`；`Pagination` 为 `dto/ApiResponse` 内的静态嵌套 record/class：`(long total, int limit, int offset, boolean hasMore)`。
- 未分页的响应 `pagination` 必须为 `null`，且用 `@JsonInclude(NON_NULL)` 不输出该键。
- **不新增必填字段、不改变 `data` 的类型**（列表接口仍是数组，不是 `{items:[]}`）。

请求参数约定（query，全部可选）：`limit`（默认 20，上限 200）、`offset`（默认 0，负数按 0）。
`util/Paging` 增加 `static Pageable pageable(int offset, int limit, Sort sort)` 与 `static Pagination meta(long total, int limit, int offset)`；
保留原有 `slice()`（其他调用方仍在使用），但新代码不得再用内存切片做列表主路径。

## 2. 知识库版本化数据模型（C）

表 `rag_document_version`（V10）：

| 列 | 类型 | 说明 |
|---|---|---|
| id | BIGINT PK | |
| document_id | BIGINT NOT NULL | → `rag_document.id` |
| version_no | INT NOT NULL | 从 1 递增，同文档内唯一 |
| content | LONGTEXT NOT NULL | 该版本的正文快照 |
| fingerprint | VARCHAR(64) | 该版本指纹（沿用 sha256(title+city+content)） |
| status | VARCHAR(16) NOT NULL | `ACTIVE` / `INACTIVE` |
| user_id | BIGINT NULL | 复刻文档归属，便于隔离检索 |
| created_at | TIMESTAMP NOT NULL | |

唯一键 `uk_rag_version (document_id, version_no)`；索引 `idx_rag_version_active (document_id, status)`。

`rag_chunk` 增列 `version_id BIGINT NULL`（V10 内 `ALTER TABLE`），指向上表 id；旧数据留空但必须仍可检索——
**兼容规则**：向量检索只装载「`chunk.version_id` 为空，或该 version 为 ACTIVE」的块。

不变量：
1. 同一文档任意时刻**至多一条** `status='ACTIVE'` 的版本；
2. 内容指纹变化 → 旧 ACTIVE 置 INACTIVE、插入新版本并设为 ACTIVE（**不再删旧版正文与块**）；
3. 回滚 = 目标版本置 ACTIVE、其余置 INACTIVE，并把该版本的正文/块重新装载进向量库（块缺失时用版本正文重建）。

HTTP（沿用现有 `ApiResponse`，挂在 `LearningController` 下）：
- `GET /api/knowledge/documents/{id}/versions` → `[{versionNo, status, fingerprint, createdAt, active}]`，按 versionNo 倒序；
- `GET /api/knowledge/documents/{id}/versions/{versionNo}` → 单个版本含正文；
- `POST /api/knowledge/documents/{id}/rollback/{versionNo}` → `{activeVersionNo}`；
- 个人知识库文档（`user_id` 非空）必须校验归属：非本人 404。

## 3. 城市名匹配（D）

新增 `util/CityNames.java`（**纯静态、无 Spring 依赖、可单测**）：

```java
public static boolean matches(String query, String name);      // 包含关系 + 短边 >= 2
public static String longestMatch(String text, Collection<String> names); // 最长命中，无命中 null
public static Optional<String> nearest(String query, Collection<String> names, int maxDistance); // 编辑距离兜底
public static String normalize(String name);                    // 去空白、去「市/地区/特别行政区」后缀
```

规则（与既有 `OnlineCityService.matchesCity` 逐字一致，不要改语义）：
1. 双方 `normalize` 后非空；
2. 完全相等 → 命中；
3. 一方包含另一方，且**较短一方长度 ≥ 2** → 命中；
4. `nearest` 仅用于目录内 4 城（上海/北京/杭州/石家庄）的 typo 兜底：编辑距离 ≤ 1 且长度 ≥ 2 才认，命中多解则返回空（不猜）。
5. 单字（"阳"/"州"/"北"）**任何时候都不得**命中任何城市。

`longestMatch` 用于「杭州西湖」这类文本：取最长城市名，避免按数组顺序 first-match。

## 4. 会话记忆（E）

配置键（Java 默认值，交给集成方写进 yml）：
- `travel.conversation.history-limit`（默认 `10`，条数，不是轮数）
- `travel.conversation.retention-days`（默认 `7`）

语义要求：
- 文档与 README 一律写「最近 10 条消息（约 5 轮）」，不得再写「10 轮」；
- 清理必须由 `@Scheduled`（每日一次）兜底，不能只在写入时顺带清理；`@EnableScheduling` 已存在则复用，不存在则在 `config/` 下自己的类上开；
- `recent()` 超过 limit 时必须截断到最新 limit 条（现有实现先取 20 再截 10 的写法要统一）；
- 新增测试：插 12 条 → `recent()` 正好 10 条且是最新 10 条；回溯 8 天的行 → 清理任务删除、7 天内保留。

## 5. 前端分页（F）

- `src/api/client.ts` 的 `get` 支持 `params?: Record<string, string|number|undefined>`；
- 账单页（`src/pages/Expense` 或对应页面）与备忘页接 `limit=20&offset=N`，底部给「共 N 条 · 上一页/下一页」，读 `pagination.hasMore/total`；
- **兼容**：响应无 `pagination` 时按"单页、无更多"渲染，不能报错；
- 登出时调用 `clearAccessTicketCache()`（`src/api/ticket.ts`）；
- `maplibre-gl` 版本锁死为精确 `6.15.0`（去掉 `^`），不要 `npm install`；
- `BudgetExecution` 展示 `executionRate`（使用率），并补一个组件单测断言「行程外」与 `+/-` 号。