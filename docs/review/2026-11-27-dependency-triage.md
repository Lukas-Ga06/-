# 依赖分诊：前端 moderate 归零、后端 4 个包覆盖到修复版本（2026-11-27）

> 快照日期：2026-11-27 ｜ 范围：GitHub 提示的「默认分支 3 个 moderate」+ 一次完整的按版本分诊
> ｜ 结论：**前端 `npm audit` 7 moderate → 0（并顺势把门禁收紧到 moderate）；后端按 OSV 查出
> 24 条公告 / 8 个包（8 CRITICAL、6 HIGH、10 MODERATE），把其中 4 个包顶到同线修复版本后剩
> 2 条（Spring 6.2.19 的两条 CRITICAL，属既有豁免，修复要 Boot 4）**。
> ｜ 动因：上一批推送时 GitHub 提示默认分支 3 个 moderate；而 CI 的 Trivy 带 `ignore-unfixed`、
> `npm audit` 阈值是 high，都不回答「我们实际引用的版本有没有已知漏洞」。

## 0. 结论速览

| 项 | 前 | 后 | 证据 |
| --- | --- | --- | --- |
| 前端 `npm audit` | 7 moderate | **0** | `npm audit --audit-level=moderate` 退出码 0 |
| CI 前端门禁阈值 | `--audit-level=high` | **`--audit-level=moderate`** | `.github/workflows/ci.yml`、[CI §5](../guide/ci.md) |
| 后端 OSV（181 个运行时依赖） | 24 条 / 8 个包（8C/6H/10M） | **2 条 / 1 个包**（Spring 已豁免） | 本文件 §2 |
| 后端 Trivy（CI 同参） | 未在本地复跑 | **2 CRITICAL**，均为已豁免的 Spring 6.2.19 | 本文件 §3 |
| 后端测试 | — | `mvn -B verify` **735/735**（5 跳过） | 本文件 §3 |

## 1. 前端：两类根因分开处理

### 1.1 `vitest` 家族 → 4.1.12

`vitest` / `@vitest/mocker` / `@vitest/coverage-v8` 三条 moderate 来自两条公告：
GHSA-qxg6-89mg-rqv8（未鉴权的手动 mock 注册导致代码注入）与 GHSA-xp26-3454-mjgp
（`@vitest/mocker` 的 Windows 反斜杠绕过重定向白名单）。二者都在 **4.1.12** 修复，4.1.11 → 4.1.12 是补丁级。

### 1.2 `glob` / `minimatch` → 只能顶 major

这两条经 `vite-plugin-pwa → workbox-build@7.4.1 → glob ^11.0.1 → minimatch 10.x` 带入。
公告区间是 glob `9.0.0–14.0.1`、minimatch `7.0.0–<11.0.1`，而 workbox-build 目前**没有**依赖
`glob >=14.0.2` 的版本，所以上游无解，只能用 `overrides`：

```json
"overrides": { "workbox-build": { "glob": "^14.0.2" } }
```

敢顶 major 的依据不是「应该没事」，而是**产物逐项不变**：

| 证据 | 升级前 | 升级后 |
| --- | --- | --- |
| `dist/sw.js` | 5289 B / 82 条 precache | **5289 B / 82 条**（完全一致） |
| 首屏 JS 合计 | 598,949 B | **598,949 B** |
| `npm test` | 557 | **557** |
| `e2e:isolated`（含 PWA manifest/sw 可达） | 14/14 | **14/14** |

`workbox-build` 只用 `globSync(pattern, {cwd, follow, ignore})` 扫 `dist/`，输入是自家构建产物，
不是用户输入；glob 14 仍 exports `globSync` 且保留这三个选项。override 只作用于构建期，
不改变 ship 出去的代码。

### 1.3 顺带把门禁收紧

CI 注释里写着「升级后应把级别收紧到 moderate」。现在 `npm audit` 为 0，就把
`--audit-level=high` 改成 `--audit-level=moderate`（R8：门禁只收紧）。以后出现 moderate
要么修、要么走 [CI §6](../guide/ci.md) 的临时豁免流程。

## 2. 后端：按「解析后的真实版本」查公告

### 2.1 为什么不能只看 Trivy 的绿灯

CI 的 Trivy 参数是 `severity: CRITICAL,HIGH` + `ignore-unfixed: true`：
「无修复版本」的条目被丢掉，且公告晚于上一次扫描时也不会回头看。它回答的是
「有没有**可修且高危**的漏洞」，不是「当前依赖树里有没有已知漏洞」。

### 2.2 方法

`mvn dependency:list -DincludeScope=runtime` 得到 **181 个解析后的运行时依赖** →
逐条 `POST https://api.osv.dev/v1/querybatch`（`ecosystem=Maven`）→ 对命中的 advisory 取 severity。
先确认**候选修复版本本身 CLEAN**，再改 `pom.xml`。

### 2.3 结果

分诊前：**8 个包 / 24 条公告（8 CRITICAL、6 HIGH、10 MODERATE）**。

| 包（Boot 3.5.16 BOM 默认） | 带入方 | 覆盖为 | 依据 |
| --- | --- | --- | --- |
| `org.apache.tomcat.embed:tomcat-embed-core` `10.1.55` | starter-web | `tomcat.version=10.1.62` | 10.1 线最新，OSV **CLEAN** |
| `io.netty:*` `4.1.135.Final` | lettuce（Redis）/ AWS SDK s3 | `netty.version=4.1.141.Final` | 4.1 线最新，OSV **CLEAN** |
| `org.apache.logging.log4j:log4j-api` `2.24.3` | spring-boot-starter-logging | `log4j2.version=2.26.1` | OSV **CLEAN** |
| `org.apache.commons:commons-lang3` `3.17.0` | springdoc → swagger-core | `commons-lang3.version=3.22.0` | OSV **CLEAN** |
| `org.springframework:spring-webmvc` `6.2.19` | Boot BOM | **不覆盖** | 6.2 线最新仍受影响，修复在 Spring 7 / Boot 4 |

覆盖后：**2 条 / 1 个包**——就是 `.trivyignore.yaml` 里早已登记的两条 Spring CRITICAL
（CVE-2026-47884 XsltView、CVE-2026-47890 SSE view fragment），豁免理由与到期（2026-12-31）不变。

写法与既有的 `jackson-bom.version` 覆盖完全一致：同一维护线上的已修版本，不跨大版本；
注释里写清「升级 Boot 后先确认 BOM 是否已包含修复版本，再删条目」。

## 3. 验证

| 项 | 命令 | 结果 |
| --- | --- | --- |
| 前端依赖 | `npm audit --audit-level=moderate` | **0 vulnerabilities**，退出码 0 |
| 前端用例 | `npm test` | 54 文件 / **557 通过** |
| 前端门禁 | `typecheck` / `lint` / `format:check` | 通过（lint 0 error，47 条既有 warning） |
| 前端构建 | `npm run build:check` | 全预算通过；首屏 598,949 / 600,000 B；precache 82 项 |
| 端到端 | `npm run e2e:isolated` | **14/14**（含 PWA 资源可达） |
| 后端 | `mvn -B verify` | **735 用例 0 失败**（5 跳过）；JaCoCo / Checkstyle / SpotBugs 全过 |
| 解析版本 | `mvn dependency:list` | tomcat **10.1.62**、netty **4.1.141.Final**、log4j-api **2.26.1**、commons-lang3 **3.22.0** |
| 后端 Trivy（CI 同参） | `trivy fs --scanners vuln --severity CRITICAL,HIGH --ignore-unfixed` | **2 CRITICAL**，均为已豁免的 Spring 6.2.19 |
| 后端 OSV 复扫 | querybatch（181 依赖） | **1 个包 / 2 条**（同上两条） |

## 4. 边界与没做的

1. **Spring 6.2.19 的两条 CRITICAL 没有在本批解决**：6.2 线最新就是 6.2.19，修复版本是 Spring 7
   （Trivy 标 `Fixed Version 7.0.9`），即 Boot 4 迁移。现有豁免基于「攻击前提不成立」（纯 JSON API，
   无 `@Controller`/ViewResolver/XsltView、无 view fragment），不是「修好了」。
2. **GitHub 提示的「3 moderate」无法直接核对**：本机没有 `gh`，且当前网络到 github.com 间歇不通；
   Dependabot alerts 端点需要认证。本批按 `npm audit` + OSV 两条独立路径把能查的都查了，
   默认分支上若还有 Maven 侧的 moderate，会在下次 OSV/Trivy 复跑时暴露。
3. **没有把 OSV 分诊加进 CI**：它是「按版本查全量公告」的离线动作，每次都要解析 181 个依赖；
   本轮只沉淀了可复现命令（[CI §5.2](../guide/ci.md)）。要不要做成 job 需要单独评估耗时与噪声。
4. **本地 Trivy 首次运行需要下载 138 MB 漏洞库**，且必须在可写 TMP 下跑；本次用
   `.evidence/trivy-tmp` + `.evidence/trivy-cache` 绕开了会话临时目录的权限问题（证据目录已 gitignore）。
5. **`overrides` 是维护负担**：workbox-build / vite-plugin-pwa 一旦跟进，就要删掉它并重跑三项证据
   （产物对比、全量用例、`npm audit`），不要把 override 当永久补丁。

## 5. 复现

```bash
# 前端
cd travel-agent-web && npm ci --ignore-scripts && npm audit --audit-level=moderate
npm test && npm run build:check && npm run e2e:isolated

# 后端：解析版本 → OSV 查公告 → 覆盖 → 再验证
cd travel-agent-server
mvn -B -q dependency:list -DoutputFile=../.evidence/deps.txt -DincludeScope=runtime
# 对每个 groupId:artifactId + version 调 OSV querybatch（ecosystem=Maven），
# 命中的 advisory 取 database_specific.severity；候选修复版本必须再查一次并 CLEAN
mvn -B verify
.trivy/trivy.exe fs --scanners vuln --severity CRITICAL,HIGH --ignore-unfixed --exit-code 1 travel-agent-server
```

## 6. 相关

- 门禁与豁免流程：[`docs/guide/ci.md` §5/§6](../guide/ci.md)
- 豁免清单：[`travel-agent-server/.trivyignore.yaml`](../../travel-agent-server/.trivyignore.yaml)
- 依赖清单与治理记录：[`docs/operations/github-security-settings.md`](../operations/github-security-settings.md)