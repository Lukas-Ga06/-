# 文档生命周期与写作规范

> 状态：当前事实 | 适用版本：0.1.0（Spring Boot 3.5.12 / Node 20） | 最后验证：2026-11-19 | 事实来源：README.md、docs/、scripts/docs-check.mjs、travel-agent-server/pom.xml、travel-agent-web/package.json

本文定义仓库里**每类文档该写什么、写在哪、怎么标注、什么时候算过期**。目标只有一个：读者能分清「当前事实」与「某天的快照」，并且链接和命令都能被机器校验。

## 1. 文档分层

| 位置 | 角色 | 写法 | 元数据 |
| --- | --- | --- | --- |
| `README.md` | **入口**：一句话定位、5 分钟快速启动、核心闭环、文档入口、最小配置、测试入口 | 短、可复制命令、细节全部外链 | 不写元数据块；命令必须可执行 |
| `docs/guide/` | **当前事实**：架构、API、数据模型、前端、部署、代码结构、快速上手、MCP、迭代账本、本文 | 与源码同步维护；断言可复核 | `状态：当前事实 + 适用版本 + 最后验证 + 事实来源` |
| `docs/review/` | **带日期快照**：评审 / 验收 / 韧性 / 决策记录 | 保留原文，不回写；结论变化用新快照 | `快照日期 + 验证范围 + 结论` |
| `docs/proposals/` | **方案契约与提案**：并行开发契约、未排期提案 | 保留原文；只改状态标注 | `状态（未落地 / 已落地（历史）/ 已废弃）+ 日期` |
| `docs/*.md`（产品 / 开发手册 / 变更记录等） | 长期有效的产品与工程约定 | 按主题维护；历史批次留在 CHANGELOG | 顶部说明适用范围即可 |
| `docs/design/` | 某次设计盘点的**带日期快照** | 不追更；新盘点另起文件 | 文件内写盘点日期与范围 |

判断方法：**读者要照着做 → guide；读者要知道「某天验了什么」→ review；读者要看「当时约定了什么」→ proposals；读者只想知道从哪开始 → README。**

## 2. 元数据规范

### 2.1 guide（紧跟 H1，空一行）

```markdown
> 状态：当前事实 | 适用版本：0.1.0（Spring Boot 3.5.12 / Node 20） | 最后验证：YYYY-MM-DD | 事实来源：<文件或目录>、<文件或目录>
```

- `最后验证`：真正按当前源码/配置核对过的日期，不是文件修改日期。
- `事实来源`：**具体到文件或目录**（如 `travel-agent-server/src/main/java/com/travel/agent/entity/`、`docker-compose.yml`、`vite.config.ts`），至少 2 个；不要写「见源码」。
- 历史性文档（如 `v2.2-iteration.md`）在 `状态` 后加限定：`当前事实（迭代历史记录，正文不回写）`。

### 2.2 review（紧跟 H1）

```markdown
> 快照日期：YYYY-MM-DD ｜ 验证范围：一句话 ｜ 环境：OS / JDK / Node / 数据库 ｜ 结论：一句话（仍待办写这里）
```

### 2.3 proposals（紧跟 H1）

```markdown
> 状态：未落地 / 已落地（历史）/ 已废弃 | 日期：YYYY-MM-DD | 说明：一句话（已落地写批次，已废弃写取代文档）
```

### 2.4 行内事实标注

- 引用源码：`类名#方法名:行号` 或 `文件:行号`，确保当前 HEAD 可检索。
- 引用数字（用例数、体积、耗时）：写明来源与日期（CI、`.e2e/`、`.evidence/`），**不要在 README 维护静态计数**。
- 外部事实（第三方能力、价格、授权、上游版本）：标「待确认」并给链接，不写成承诺。

## 3. 新增文档

1. 先按 §1 判断层级；同一事实只在一个地方详细写，其余位置链接过去。
2. 文件名用 kebab-case 英文（如 `quality-gates.md`）；review 用 `YYYY-MM-DD-主题.md`。
3. 写 H1 + 对应元数据 + 事实来源；中文正文，命令用围栏代码块。
4. 挂到入口：`docs/guide/README.md` 索引 + 根 `README.md` 文档入口表（仅重要文档）+ 必要时 `docs/review/README.md` / `docs/proposals/README.md` 状态表。
5. 运行 `node scripts/docs-check.mjs`，0 断链、0 失效命令后再提交。

## 4. 更新文档

- **同一次改动同时改代码与文档**；不允许「代码已改、文档下批再说」。
- 更新内容后同步刷新 `最后验证`；事实来源变了（换文件/目录）一并更新。
- 改一个事实前先全局搜索：`grep` 类名、端点、环境变量、表名，避免同一事实在多份文档里只改一处。
- 不在多份文档复制易漂移的静态计数、逐接口字段表、逐文件清单；用链接指向唯一权威页。
- 修订 guide 不改变其「当前事实」属性；若结论只对某天成立，应写成 review 快照而不是改 guide。

## 5. 过期与归档

| 情况 | 处理 |
| --- | --- |
| guide 内容与源码不一致 | 立即更新 guide；来不及核对的段落删掉或改成指向权威页，**不要留旧断言** |
| 某份 guide 只反映历史状态 | 降级为 `docs/review/YYYY-MM-DD-主题.md` 快照，在 guide 索引里改为链接快照 |
| review 结论被新验证推翻 | 保留旧快照；新建新日期快照，并在旧文件头部加一行「已被 <新快照> 取代」 |
| proposal 已落地 | 标 `状态：已落地（历史）`，落地记录链到 `docs/CHANGELOG.md`；长期事实沉淀进 guide |
| proposal 不再实施 | 标 `状态：已废弃` 并写取代它的文档；**不删除正文** |
| 文档整体作废 | 保留文件并在 H1 下标注「已废弃 + 日期 + 替代文档」；本仓库暂不设 archive 目录，历史内容就地保留 |

原则：**只标注，不删除**；历史是追溯依据，删除会让旧结论失去上下文。

## 6. 校验（docs-check）

零依赖 Node 脚本，扫描根 `README.md` 与 `docs/**/*.md`：

```bash
node scripts/docs-check.mjs          # 人类可读；有断链/失效命令时 exit 1
node scripts/docs-check.mjs --json   # CI 消费的 JSON（stdout 只有 JSON）
```

校验内容：

1. **相对链接与图片**：目标文件/目录必须存在；忽略 `http(s)`、`mailto:`、`tel:`、`data:`、纯锚点与模板占位；代码块内的 `[x](y)` 不当链接。
2. **npm 命令**：`npm run <script>`、`npm --prefix <dir> run <script>`、`npm test` 的 script 必须存在于对应 `package.json`（默认 `travel-agent-web/package.json`）。
3. **Maven 命令**：`mvn <goal>` 的 goal 需在生命周期 / 常用插件白名单内；未知 goal 只警告、不失败。

输出统计：扫描文件数、相对链接数、命令数（npm / mvn 分列）、断链清单、失效命令清单、警告清单。

CI 接入方式（尚未写入 workflow，需单独评审）：

```yaml
- name: Docs check
  run: node scripts/docs-check.mjs
```

合并前至少本地跑一次；改了链接、命令或目录结构时必须重跑。

## 7. 提交前自查

- [ ] 文档层级选对（guide / review / proposals / README），没有把快照写成「当前事实」。
- [ ] H1 下有对应元数据，`最后验证` 是本次核对日期，事实来源具体到文件/目录。
- [ ] 新增/修改的链接可点开；命令在 `pom.xml` / `package.json` 中真实存在。
- [ ] 没有复制第二份静态计数或逐接口字段表；细节已链接到唯一权威页。
- [ ] 历史 review / proposal 正文未被改写，只加了状态或头部标注。
- [ ] `node scripts/docs-check.mjs` 通过（exit 0）。

相关入口：[文档索引](README.md) · [评审快照规范](../review/README.md) · [方案状态表](../proposals/README.md) · [根 README](../../README.md)