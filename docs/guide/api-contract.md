# OpenAPI 契约与前端类型

> 状态：当前事实 | 适用版本：0.1.0（Spring Boot 3.5.7 / Node 20） | 最后验证：2026-11-19 | 事实来源：`travel-agent-server/src/main/java/com/travel/agent/config/OpenApiConfig.java`、`travel-agent-server/src/test/java/com/travel/agent/contract/OpenApiSnapshotTest.java`、`docs/api/openapi.json`、`travel-agent-web/src/api/generated/schema.d.ts`、`travel-agent-web/scripts/check-openapi-types.mjs`、`travel-agent-web/src/types/api-contract.ts`

本文只讲**机器契约的生成、校验与破坏性变更流程**。每个接口的业务语义、错误码、字段口径仍以
[api-reference.md](api-reference.md) 为准；两者关系见第 7 节。

---

## 1. 结论先行

- 契约的**唯一事实来源是后端代码**：Controller 的注解 + `dto/` 下的类型。
- `GET /v3/api-docs` 是这份事实的机器可读出口；`docs/api/openapi.json` 是它的**入库快照**。
- 前端 `src/api/generated/schema.d.ts` 从快照生成；`src/types/api-contract.ts` 用生成类型对手写类型做**编译期断言**。
- 任何人改了 DTO/Controller，都必须重新生成快照与前端类型；CI 用「快照语义比较 + 类型漂移检查」拦下忘记生成的情况，用 `oasdiff breaking` 拦下破坏性变更。

```
Controller + dto/  ──springdoc──▶  GET /v3/api-docs  ──OpenApiSnapshotTest──▶  docs/api/openapi.json
                                                                                      │
                                                              openapi-typescript ◀────┘
                                                                      │
                                                                      ▼
                                              src/api/generated/schema.d.ts（禁止手改）
                                                                      │
                                                        类型级断言 ◀───┴──▶ src/types/api-contract.ts
```

## 2. 产物清单

| 产物 | 生成方式 | 是否入库 | 校验手段 |
| --- | --- | --- | --- |
| `docs/api/openapi.json` | 后端 `OpenApiSnapshotTest` + `-Dopenapi.update=true` | 是 | 默认模式与 `/v3/api-docs` 做语义比较 |
| `travel-agent-web/src/api/generated/schema.d.ts` | `npm run api:generate`（openapi-typescript 7.x） | 是 | `npm run api:check` 重新生成到临时文件逐行比较 |
| `travel-agent-web/src/types/api-contract.ts` | 手写（类型级断言，无运行时代码） | 是 | `npm run typecheck` |
| `/swagger-ui/index.html` | springdoc 自动提供 | 否（运行时） | 人工阅读，仅本地/受控环境使用 |

当前快照规模（以实际生成为准，不必手改）：`paths` 52 条、`components.schemas` 98 个、约 110 KB；生成的前端类型约 86 KB。

## 3. 生成 / 更新

前提：JDK 17 + Maven 3.9、Node 20 + `npm install`（`openapi-typescript` 已在 devDependencies）。测试使用 `test` profile（H2 内存库），不访问网络、不调用真实大模型。

```bash
# 1) 后端：用当前代码重建契约快照（会真实启动一次内嵌 Tomcat，RANDOM_PORT）
cd travel-agent-server
mvn -B -Dtest=OpenApiSnapshotTest -Dopenapi.update=true test

# 2) 前端：从快照生成 TypeScript 类型
cd ../travel-agent-web
npm run api:generate
```

只做类型生成而不改后端时，第 1 步可以省略——但**不允许**跳过第 2 步就提交 DTO 改动。

### 3.1 快照为什么可重复

`OpenApiSnapshotTest` 写盘前对文档做规范化，因此同一份代码连续生成两次**字节一致**：

- 递归按 key 字典序排列所有对象（Jackson `ORDER_MAP_ENTRIES_BY_KEYS` + 显式 TreeMap）；
- 顶层 `tags` 数组按 `name` 排序（springdoc 的 tag 顺序来自 bean 扫描，无语义）；
- 2 空格缩进、UTF-8、结尾一个换行。

测试内的 `apiDocsCanonicalizationIsRepeatable()` 会连续取两次文档并断言规范化结果相同，防止随机漂移。

可用 `-Dopenapi.snapshot=<path>`（或环境变量 `OPENAPI_SNAPSHOT`）覆盖快照位置；默认从工作目录向上找同时含 `docs/` 与 `travel-agent-server/pom.xml` 的仓库根，因此从仓库根或模块目录运行 Maven 都写同一处。

`-Dopenapi.update=true` 同时支持环境变量 `OPENAPI_UPDATE=true`（某些 CI 对 surefire fork 的系统属性传递有限制时用）。

## 4. 校验（本地与 CI）

```bash
# 后端：读取已提交快照，与当前代码的 /v3/api-docs 语义比较；不一致打印可定位 diff 并失败
cd travel-agent-server
mvn -B -Dtest=OpenApiSnapshotTest test

# 前端：1) 类型文件是否与快照同步；2) 快照核心 paths/schemas 是否齐全
cd ../travel-agent-web
npm run api:check

# 前端：手写类型与生成类型的编译期断言（api-contract.ts）
npm run typecheck
```

- **后端比较是语义比较**：两边先解析成 JSON 再比较，空白/换行/key 顺序差异不算变更；失败信息形如 `$.components.schemas.TripResponse.properties.newField: 快照缺失，当前代码新增 "..."`，可直接定位到字段。
- **前端 `api:check`**：调用仓库内的 `node_modules/openapi-typescript`（解析包的 bin 入口，Windows/POSIX 一致，不联网、不经过 shell），生成到临时目录后与 `schema.d.ts` 比较；只忽略行尾空白、CRLF、banner 时间戳三类噪声。漂移时打印行级 diff 并 `exit 1`。
- `api-contract.ts` 的双向可赋值断言在 `tsc --noEmit` 阶段执行：字段改名、类型变化、字段消失都会编译失败（见第 6 节）。

### 4.1 常见失败怎么处理

| 现象 | 判断 | 处理 |
| --- | --- | --- |
| 后端快照测试失败，diff 只有新增字段 | 有意变更、向后兼容 | `-Dopenapi.update=true` 重建 → `npm run api:generate` → `npm run typecheck` → 三个文件一起提交 |
| diff 显示删除/改名/类型变化 | 破坏性变更 | 先按第 5 节确认影响面；必要时同步改前端调用点，版本号或迁移说明按发布流程处理 |
| 后端测试失败但人不确定是否自己改的 | 并发编辑或分支落后 | 先 `git status`/`git diff` 确认代码现状，再重建；**不要手工编辑 `openapi.json`**（它永远是生成物） |
| `api:check` 报生成文件不一致 | 有人手改了 `schema.d.ts`，或改完快照没重新生成 | 确认快照最新后 `npm run api:generate` |
| `typecheck` 在 `api-contract.ts` 报 `Type 'false' does not satisfy the constraint 'true'` | 手写类型与 schema 真漂移 | 优先改手写类型对齐；确有保留差异时只做最小 `Omit`/子集断言并写明原因，**禁止用 `any` 掩盖** |
| `api:check` 报核心 path/schema 缺失 | 路由或 DTO 被删/改名 | 确认是否破坏性变更；若有意调整，同步更新脚本里的 `CORE_PATHS`/`CORE_SCHEMAS` 与前端类型 |

> 生成物规则：`docs/api/openapi.json` 与 `src/api/generated/schema.d.ts` **只由命令生成，禁止手改**。
> 格式化/静态检查必须忽略生成目录（Prettier/ESLint 配置里忽略 `src/api/generated/`），
> 否则一次 `format:write` 就会让 `api:check` 误报漂移——真被格式化过时，重新 `npm run api:generate` 即可。

## 5. 破坏性变更检查（oasdiff）

在 PR 上比较「目标分支快照」与「本次快照」。CI 的 contract job 建议执行：

```bash
# 取 base（main）上的旧快照；首版仓库还没有 docs/api/openapi.json 时视为无基线
git fetch --no-tags --depth=1 origin main
if git cat-file -e origin/main:docs/api/openapi.json 2>/dev/null; then
  git show origin/main:docs/api/openapi.json > "$RUNNER_TEMP/openapi-base.json"
  oasdiff breaking "$RUNNER_TEMP/openapi-base.json" docs/api/openapi.json --fail-on ERR
else
  echo "首版无基线，跳过 oasdiff breaking"
fi
```

- `oasdiff` **不在本机安装**，也不需要改 pom/package.json；CI 从锁定版本的 release 二进制（或 `oasdiff/oasdiff-action`）获取，避免「本机能过、CI 版本不同」。
- `--fail-on ERR` 只拦破坏性错误；需要更严格时可加 `--fail-on WARN`，或在 job 里另跑 `oasdiff changelog` 输出变更摘要到 job summary。
- 退出码：`0` 无破坏性变更；`1` 存在 ERR 级破坏性变更；`2` 参数/文件错误（应修命令而不是放过）。
- 本地想预检时，用同一份 `git show origin/main:...` 导出的 base 文件即可；没有安装 oasdiff 就依赖 CI 结果。

**会被判破坏性（ERR）的典型改动**：删除 path/operation、删除或改名请求/响应字段、字段类型变化、把可选请求字段改成必填、收窄响应枚举、改变参数位置（path↔query）。
**通常非破坏性**：新增 path/operation、新增可选请求字段、响应新增字段、放宽校验（如 `maxLength` 变大）、描述/示例变化。
注意：本项目 Controller 的响应统一包在 `ApiResponse<T>` 里，破坏性判断以 `components.schemas` 中的 `XxxDTO`/`XxxResponse` 为准；泛型包装的 schema 名（如 `ApiResponseTripResponse`）变化通常意味着内层类型变了。

## 6. DTO 注解与 schema 的关系（改代码时看这里）

springdoc 直接从 Controller 签名与 DTO 类型推导 schema，**没有第二份手写定义**。因此：

| 后端写法 | 生成的 schema 效果 |
| --- | --- |
| `@NotNull` / `@NotBlank` / `@NotEmpty` | 字段进入 `required`（`@NotBlank` 另外给 `minLength: 0`） |
| `@Size(max = 200)` | `maxLength: 200` |
| `@Pattern(regexp = "...")` | `pattern` |
| `@DecimalMin("0")` | `minimum: 0` |
| `String` | `type: string`（没有枚举，见下方「收窄」） |
| `Integer` / `Long` | `integer` + `format: int32` / `int64` |
| `Double` / `BigDecimal` | `number` + `format: double`（`BigDecimal` 无 format） |
| `List<T>` / `T[]` | `array` + `items` |
| 嵌套静态类 / 独立 DTO 类 | 独立的 `components.schemas` 条目，通过 `$ref` 引用 |
| `ApiResponse<TripResponse>` | 生成 `ApiResponseTripResponse`（泛型参数拼进 schema 名） |
| `@JsonInclude(NON_NULL)` | **不体现**在 schema 里（OpenAPI 不表达「null 时省略」） |

由此带来的两个约定：

1. **可选/可空**：未被校验注解标记的字段在 schema 里都是可选的；`@Nullable` 才会生成 `nullable`。手写前端类型按「后端总会返回该键」声明必填是允许的，`api-contract.ts` 的 `DeepNormalize` 会去掉这层可选/可空差异再比较形状。
2. **枚举收窄**：项目里的状态字段（`phase`、`status`、`itemType` 等）后端都是 `String` 常量，schema 只有 `type: string`。前端把它们收窄成字面量联合是**前端主动行为**，`api-contract.ts` 用「子集」断言校验；后端若把取值改成别的字符串集合，前端联合若不再是子集就会编译失败。

新增/修改字段的流程：**只改 Java 代码** → 重建快照 → 重新生成 TS → 若手写类型需要同步则一起改 → `npm run typecheck`。不要为了让测试过而手工改 `openapi.json`。

## 7. 与 api-reference.md 的分工

| | 本文 + `docs/api/openapi.json` | [api-reference.md](api-reference.md) |
| --- | --- | --- |
| 定位 | **机器契约**：路径、方法、字段名/类型、必填、长度/正则约束 | **人读语义**：业务规则、字段口径、错误码、权限、示例、边界 |
| 来源 | 从代码自动推导，禁止手写 | 人工维护，逐接口对照源码 |
| 谁消费 | 前端生成的类型、OAS 工具链、`oasdiff`、第三方 SDK | 开发者、集成者、评审者 |
| 何时改 | 任何 Controller/DTO 变化 | 业务行为/语义变化；字段变化也应同步 |

同步规则：**行为变化 → 两边都改；纯类型/新增字段 → OpenAPI 与前端类型必须改，api-reference 至少补字段说明**。api-reference 顶部提供「机器契约见本文」的入口即可，不必在 api-reference 里重复枚举所有 schema。

## 8. 并发编辑与冲突处理

- 生成物冲突（多人同时改 DTO）：**不要手工 merge `openapi.json` / `schema.d.ts`**。取任意一方后，以当前代码重新生成，生成结果自然收敛；冲突解决后必须重跑第 4 节三条命令。
- 快照 diff 只应包含本次契约变化；如果混入别人的字段，说明基线不对，先同步分支再重建。
- 后端与前端分属不同提交时，先后端快照、再前端类型，避免 `api:check` 在中间态失败。
- 公开访问：`SecurityConfig` 放行 `/v3/api-docs/**`、`/swagger-ui/**`、`/swagger-ui.html` 是刻意的——文档内容只有 Controller 签名与 DTO 推导出的 schema，不含 JWT secret、LLM Key、数据库口令等任何凭证（见 `SecurityConfig` 注释）。生产环境如需收紧，应在网关/反向代理层按来源限制，而不是改成登录态，否则契约测试与工具链无法直接取文档。

## 9. 已知限制

- springdoc 2.8.13 输出 **OpenAPI 3.1**；项目未声明 `securitySchemes`，认证方式（Cookie/Bearer、SSE 票据）由 api-reference 与前端代码说明，schema 不建模。
- SSE 端点（`/api/agent/stream`）只描述请求参数，不描述事件名与事件 payload；事件契约见 api-reference 与前端 `api/agent.ts` 的 `EVENT_NAMES`。
- 路径参数命名不统一（`{id}` 与 `{tripId}` 并存）是现状；重命名属于破坏性变更，需按第 5 节评估，不要顺手改。
- 快照不包含示例值（`example`）与错误响应 schema，避免把演示数据固化进契约；错误码以 api-reference 为准。