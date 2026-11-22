# 真实模型（DeepSeek）实机调用：验了什么、怎么验的（2026-11-22）

> 快照日期：2026-11-22 ｜ 验证范围：真实 `LLM_API_KEY` 下的意图识别 / 结构化规划 / 方案润色整条链路 ｜ 环境：Windows / JDK 17 / 后端 `spring-boot:run`、H2 文件库、`RATE_LIMIT_ENABLED=false` ｜ 结论：**真实模型链路打通（`llmVerified=true`）**；默认模型名 `deepseek-chat` 在该 key 上不存在，必须用 `deepseek-flash`/`deepseek-v4-pro`；余额仅 ¥0.80，只做了 2 次调用

## 1. 为什么之前一直没验

上一轮验收快照（[2026-11-19-acceptance.md](2026-11-19-acceptance.md) §4.2）记录的阻塞原因是
「Key 存在但余额为 0：`402 Insufficient Balance`」，所以 FR-03 的自主工具调用循环只用本地
OpenAI 兼容替身验了链路。这次拿到一个**有余额的 key**（¥0.80），才能做真正的调用。

## 2. 直接结论：模型名对不上

```
GET https://api.deepseek.com/models
→ deepseek-flash (DeepSeek-V4.1-Flash)
→ deepseek-v4-pro (DeepSeek-V4-Pro)
```

而仓库默认配置是 `LLM_MODEL:deepseek-chat`（`application.yml:215`）。
**`deepseek-chat` 在这套 API 上已经不在模型列表里** —— 用默认值启动会拿不到模型。
本次验收用 `LLM_MODEL=deepseek-flash` 覆盖。

> 维护提示：`application.yml` 的默认值建议改成当前在售的模型名，或在启动时把
> 「模型不存在」错误明确报出来（现状会落进「降级为规则模板」的通用分支，容易被误读成网络问题）。
> 这是本次验收暴露的**未修项**，因为改默认模型名属于部署口径变更，应由你决定。

## 3. 验了什么

### 3.1 结构化规划（真实调用，23s）

```
POST /api/agent/chat
{"message":"帮我规划上海出发的杭州周末两日游，两人，预算3000以内",
 "conversationId":"real-llm-smoke-1"}

→ code=0, intent=PLAN_TRIP, status=NEED_CONFIRMATION, elapsed=23s
```

方案正文（模型产出，节选）：

> 我已经帮你把「杭州2日游」规划好了（11月28日出发，2人）：一共4个景点、4顿美食，
> 预计总花费 ¥1204，在你 ¥3000 的预算内。你可以看看时间轴和地图，确认没问题后就能保存行程啦。
> ✅ 已自动重规划 1 次：Day 1 把「用餐 · 楼外楼」及之后的安排整体后移 35 分钟（转场驾车约 45 分钟）；…

这条回复里有两类信号：**结构化产物**（景点/餐饮条数、预算合计）与**审核驱动的自动重规划**
（时间冲突被检出后自己挪时间并说明原因）。生成 4 个景点 + 4 顿餐饮 + 预算测算，
不是规则模板能拼出来的形态。

### 3.2 运行态自报：`llmVerified=true`

项目为此专门做了「配置态 / 运行态分离」（P5：只看 `llmConfigured` 会在 Key 失效时自报失真）：

```
GET /api/system/status
llmConfigured = True
llmVerified   = True      ← 最近一次真实调用成功
llmModel      = deepseek-flash
llmHint       = 已接入大模型：意图识别、方案生成与文案润色走模型
routeProvider = estimated
priceProvider = reference-estimate
```

`llmVerified=true` 是**应用自己记录的**「上一次真实调用到底成没成」，比事后看余额更可靠。

### 3.3 失败降级没有被触发

后端日志里 `LLM 调用失败，降级为规则模板` 出现 **0 次**（`grep -c` 实测）。
如果 key 无效/模型不存在，这条 WARN 会出现并降级 —— 它没出现，说明走的是真实模型。

### 3.4 一条混合意图消息（暴露的是产品限制，不是模型故障）

```
{"message":"上次说的那个杭州的安排里，第二天下午是不是太赶了？顺便提醒我周五之前把名宿退了"}
→ intent=ADD_MEMO, status=COMPLETED, 3s
→ 📝 已添加备忘：「上次说的…顺便提醒我周五之前把名宿退了」。当前待办 37 项。
```

这条消息里其实有两件事（一个问句 + 一个提醒）。系统按**单意图路由**处理，把整句当成了备忘。
这是既有的架构约束（一条消息一个意图），不是模型识别错误；但值得记一笔：
**多意图混写时用户会得到「只做了一半」的结果，且界面不提示「还有一半没处理」**。

## 4. 顺带发现（非本次改动引入）

- **高德路径 QPS 超限**：`AmapDataService: 高德路径调用失败（infocode=10021, CUQPS_HAS_EXCEEDED_THE_LIMIT），已降级估算`。
  因此 `routeProvider=estimated`。有 key 但配额/QPS 不够，属于外部依赖容量问题。
- `priceProvider=reference-estimate` 与设计一致（ADR-006：没有实时房态就不宣称）。

## 5. 成本与边界

- 余额 **¥0.80**（充值余额，`is_available=true`），本次只做 2 次调用（1 次规划 23s、1 次备忘 3s），
  余额显示未变化（小数位只到 0.01，小额调用看不出来）。
- **未验**：FR-03 的「Agent 自主工具调用循环」（RESEARCH 阶段自主选工具 → 观察 → FINAL）。
  它在上一轮是用本地替身验的；真实模型下要验它，需要多轮工具调用，成本更高，
  且余额不足以支撑反复调试。建议冲值后再补，或用 `deepseek-v4-pro` 单独跑一次。
- **未验**：长上下文（1M window）下的表现、并发调用下的配额闸门、模型超时（30s 读超时）边界。

## 6. 证据

`.evidence/real-llm/`：

| 文件 | 内容 |
| --- | --- |
| `plan-response.json` | 规划请求的完整响应（含 planId、diff、预算） |
| `plan-message.txt` | 模型生成的方案说明原文 |
| `second-response.json` / `second-message.txt` | 混合意图那条的响应与回复 |
| `system-status.json` | `/api/system/status` 快照（`llmVerified=true`） |

后端日志：`.evidence/real-llm-backend.log`（含高德 QPS 降级的 WARN）。
**密钥不入库、不进日志**：只通过 `LLM_API_KEY` 环境变量注入，验收结束后已清理临时文件。