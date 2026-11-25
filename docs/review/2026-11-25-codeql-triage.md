# CodeQL 67 条告警分诊（2026-11-25）

> 快照日期：2026-11-25 ｜ 验证范围：`main` 上 2026-11-24 入库的 67 条 open 告警（java-kotlin 64 + javascript-typescript 3），规则集 `security-and-quality` ｜ 环境：GitHub CodeQL advanced workflow；ReDoS 在 Windows 11 / JDK 17 用 JVM 探针复现 ｜ 结论：**23 条修复（PR #29）、6 条按理由驳回、38 条质量类保留**；16 条 high 中 13 条修复、3 条为协议/配置性接受风险。

## 1. 分诊口径

- 来源：`GET /repos/Lukas-Ga06/-/code-scanning/alerts?state=open`（2026-11-25 取数，67 条）。
- 安全严重度：high 16 / medium 10；另按规则自身严重度还有 warning 7 / note 33 / error 1（两套维度，不要混用）。
- 高危/中危**逐条读代码**；ReDoS 不信规则名，按接口允许的 20,000 字符上限用 JVM 探针实测最坏耗时后再定处置。
- 处置只有三类：**修复**（代码 PR）、**驳回**（`false positive` / `won't fix`，必须在 GitHub 上带理由）、**保留**（质量 backlog，不影响合并；CodeQL 告警本来就不是必需检查）。

## 2. 高危 16 条

| alert | 规则 | 位置 | 处置 |
| --- | --- | --- | --- |
| #4 #5 #7 #8 #9 #10 #11 #12 #13 #63 #64 | `java/polynomial-redos` | `MessageParser.java` 11 处 | **修复**（PR #29）：无界量词改有界；实测见 §5 |
| #16 | `java/xss` | `WeChatOfficialAccountController.java:81` | **修复**：握手回显 `echostr` 显式 `text/plain` |
| #24 | `java/tainted-arithmetic` | `MessageParser.java:719` | **修复**：`base + offset` 先 long 夹取再转 int |
| #15 | `java/spring-disabled-csrf-protection` | `SecurityConfig.java:66` | **驳回（won't fix）**：无状态 JWT（Cookie `SameSite=Lax` + `Authorization` 头），无 CSRF 依赖的服务端会话 |
| #27 | `java/potentially-weak-cryptographic-algorithm` | `WeChatOfficialAccountController.java:200` | **驳回（won't fix）**：SHA-1 是微信公众号接入签名的协议要求，只用于验签 |
| #1 | `js/empty-password-in-configuration-file` | `application.yml:24` | **驳回（false positive）**：本地 H2 文件库 `sa` 空密码；生产 MySQL 口令走环境变量 |

## 3. 中危 10 条

| alert | 规则 | 位置 | 处置 |
| --- | --- | --- | --- |
| #17 #18 #19 #20 #21 #22 #23 | `java/log-injection` | `TrustedProxies` / `PendingPlanStore` / `LlmQuotaService` / `RedisRateCounter` / `PlanConfirmationService` | **修复**：用户可控值进日志前剥 `\r`/`\n` |
| #25 #26 | `java/local-temp-file-or-directory-information-disclosure` | `TripGuidebookService.java:257,273` | **驳回（false positive）**：classpath 字体临时文件不含凭据；POSIX 默认 600 + `deleteOnExit` |
| #14 | `java/error-message-exposure` | `WeChatOfficialAccountController.java:173` | **驳回（false positive）**：返回的是 Agent 的用户可见回复；异常路径统一替换为通用文案 |

## 4. error / warning / note

- **#59 `java/index-out-of-bounds`（规则严重度 error）→ 修复**：`PlannerDraftSupport.buildArgs` 对奇数长度 `kv` 直接抛 `IllegalArgumentException`，不再 `kv[i+1]` 越界。
- **#57 #69 `java/dereferenced-value-may-be-null` → 修复**：INTENT / PLAN 阶段返回 null 时抛 `IllegalStateException`，由统一异常处理转成用户可见的通用错误，不再 NPE。
- **#58 `java/random-used-once`、#60 #61 `java/constant-comparison`、#62 `java/thread-start-in-constructor` → 保留**：不是可利用漏洞；属于可维护性/时序审查项，后续按需处理。
- **note 33 条 → 保留（质量 backlog）**：`unused-parameter` 12、`uncaught-number-format-exception` 11、`call-to-object-tostring` 4、`internal-representation-exposure` 3、`missing-override-annotation` 2、`js/unused-local-variable` 1。
  - 其中 `uncaught-number-format-exception` 属于**健壮性**（恶意/畸形数字串可能 500），建议下一个清理批次按调用点补 `try`/默认值，而不是本批次一并改。
  - 质量类 note 不驳回：保留可见性，避免用「批量 dismiss」把真实债务藏起来。

## 5. ReDoS 实测（20000 字符 = 接口允许的消息上限）

| 正则（修复前） | 最坏耗时 | 修复后同一输入 |
| --- | --- | --- |
| `DAY_IN_TEXT`：`[0-9一二两三四五六七八九十]+(?:天\|日)` | **19.2 s** | **5.0 ms**（`{1,4}`） |
| 记账金额：`[0-9]+(?:\.[0-9]+)?\s*(?:元\|块钱\|块)` | **15.4 s** | **8.4 ms**（`{1,9}`/`{1,2}`） |
| 时间：`(上午\|…)?\s*(\d{1,2})\s*[点时：:]\s*…` | **4.5 s**（全空白） | **1.9 ms**（`\s{0,4}`） |
| 天数：`(\d+)\s*[天日]` | **1.9 s** | **2.2 ms**（`\d{1,3}`） |

> 这不是理论风险：在 20,000 字符上限下，一个请求就能占住工作线程十几秒。修复只收紧量词，不改变正常输入（天数 ≤ 3 位、金额 ≤ 9 位、空白 ≤ 4 个）的解析结果。

## 6. 处置统计与复核

| 处置 | 数量 | 明细 |
| --- | --- | --- |
| 修复（PR #29） | **23** | ReDoS 11 + log-injection 7 + xss 1 + index-OOB 1 + null-deref 2 + tainted-arithmetic 1 |
| 驳回 | **6** | #15 #27（won't fix）、#1 #14 #25 #26（false positive），理由已写入 GitHub 告警 |
| 保留 | **38** | 质量 backlog 33 note + warning #58 #60 #61 #62（4 条）+ 1 条计数差：见 §4 |

复核方式：PR #29 合并后 `main` 的 CodeQL 分析会重新入库，已修复项随之关闭；`GET /code-scanning/alerts?state=open` 应只剩「保留 + 驳回不计入 open」的 38 条。若某个已修复项仍 open，按 §5 的探针与代码 diff 再定位。

## 7. 后续建议

1. 质量 backlog 里的 `uncaught-number-format-exception`（11 条）单独开一个健壮性批次：给 `MessageParser` / `TrustedProxies` / `EmbeddingCodec` 的数字解析补默认值或范围检查。
2. `unused-parameter` 与 `call-to-object-tostring` 属于接口兼容/日志可读性问题，建议在重构批次处理，不单独开 PR。
3. **不要**把 CodeQL 告警设为必需检查：当前 38 条质量项会让每个 PR 卡在历史债务上；真正要卡的是 `Security / 依赖漏洞` 与 `Security / gitleaks 密钥扫描` 这类确定性门禁。