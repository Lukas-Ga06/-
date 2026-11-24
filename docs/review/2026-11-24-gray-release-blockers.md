# 灰度阻断项复验：run-18 假绿、城市抽取、限流隔离（2026-11-24）

> 快照日期：2026-11-24 ｜ PR：[#17](https://github.com/Lukas-Ga06/-/pull/17)
> 范围：灰度期真实用户会撞到的两个功能问题（压测假绿、目的地抽取）+ 一个安全/容量问题（限流身份）
> 原则：只记录**实际跑出来的结果**；没跑的不写成跑过；真机与法务仍未完成，见 §5。
> 证据目录（均在 `.gitignore` 内）：`.evidence/llm-load-20/`（旧）、`.evidence/llm-load-20-cityfix/`（新）、`.evidence/rate-limit-e2e.log`。

## 0. 结论速览

| 项 | 修复前 | 修复后 | 证据 |
| --- | --- | --- | --- |
| run-18 假绿 | `status=COMPLETED`、正文「没能为『州镇江的』生成行程」，压测却记成功 | 压测按 planned/asked_back/failed/unknown 四分类；失败即 exit 1 | §1，`summary-reclassified.json` |
| 城市抽取 | 20 轮里 **13 轮**目的地不可用（4 轮拿了出发地、6 轮带脏后缀、2 轮拼了非城市名、1 轮失败） | 20 轮重跑：**15 planned + 5 asked_back + 0 failed**，目的地全部是真实城市 | §2，`.evidence/llm-load-20-cityfix/summary.json` |
| 限流隔离 | compose 信任整个 `172.16.0.0/12`，私网来源全塌成 nginx 一个桶 | 只信任 nginx 固定地址 `/32`；4 个来源各自独立分桶，伪造 XFF 不生效 | §3，`.evidence/rate-limit-e2e.log` |
| S3 | 未验证（`PHOTOS_STORE=local`） | 见 §4 | §4 |

## 1. run-18 假绿

### 1.1 现象

旧驱动 `.e2e/llm-load-20.mjs` 只按 `status` 是否属于
`{COMPLETED, NEED_CONFIRMATION, FAILED, CANCELLED}` 判「有终态」。run-18 的原始响应是：

```json
{"status":"COMPLETED","planId":"703958b0-...","confirmation":null,"tripId":null,
 "message":"抱歉，暂时没能为「州镇江的」生成行程：联网查询也没找到这座城市。…"}
```

也就是**没有任何待确认方案、没有行程，只有一句失败文案**，却因为 `status=COMPLETED` 被记为成功，
汇总里 `noTerminalRuns=[]`、红线「无终态 = 0」通过。这是假绿。

旧快照里还有一句错误归因：「6 轮快 + COMPLETED 都是槽位不足主动反问」。
逐条核对原始响应后：其中 **5 轮**确实是槽位反问（`planId=null`、`confirmation=null`、
`missingSlots=["partySize"]`），第 6 轮（run-18）是上面的失败文案。5 轮反问也不等于规划成功。

### 1.2 判定口径修正

`.e2e/llm-load-20.mjs` 现在把每轮分成四类：

| 结果 | 判定 | 含义 |
| --- | --- | --- |
| `planned` | `confirmation != null` 或 `tripId != null` | 真的产出了待确认方案/行程 |
| `asked_back` | `missingSlots` 非空，且没有失败文案 | 槽位不足，明确反问——合法终态，但不是规划成功 |
| `failed` | 失败文案 / `FAILED` / 非 200 / 有 `planId` 却无 `confirmation` 的空计划 | 真失败 |
| `unknown` | 其余 | 无法归类，同样不允许 |

红线改为 **`failed=0` 且 `unknown=0`**，否则进程 `exit 1`；`asked_back` 单独计数，不再混进成功。

### 1.3 旧证据重分类（不重跑模型）

`node .e2e/llm-load-20.mjs --classify` 对 `.evidence/llm-load-20/run-*.json` 重分类，
不覆盖原始 `summary.json`，结果写入 `summary-reclassified.json`：

| 口径 | 结果 |
| --- | --- |
| 旧：终态分布 | `NEED_CONFIRMATION` 14 ｜ `COMPLETED` 6 |
| 新：结果分布 | planned **14** ｜ asked_back **5** ｜ failed **1（run-18）** ｜ unknown 0 |
| 脏城市名 | `#3=成都吃`、`#4=发去厦门`、`#7=成都吃`、`#13=张家界的`、`#15=雪乡的`、`#16=泉州的` |
| 旧红线 | **FAIL**（failed=1，旧口径却报通过） |

### 1.4 修复后 20 连重跑

目标：本地后端（H2 + 真实 `deepseek-flash`，端口 18081），不是 mock；证据
`.evidence/llm-load-20-cityfix/`。

| 指标 | 数值 |
| --- | --- |
| 结果分布 | planned **15** ｜ asked_back **5** ｜ failed **0** ｜ unknown **0** |
| 无明确终态 | **0** |
| 429 | **0** |
| 耗时 | min **1,848 ms**（纯反问轮）／ median **30,863 ms** ／ max **34,998 ms** |
| 花费 | 余额 **¥10.27 → ¥9.63**，即 **¥0.64**（20 轮规划 + 联网城市目录生成，未单列拆分） |
| 红线 | **PASS** |

5 轮 `asked_back` 都是「还缺同行人数」，与修复前同一批消息一致；run-18 现在正常产出
「扬州2日游」待确认方案。逐轮目的地见 §2.3。

## 2. 城市抽取

### 2.1 根因

1. **出发地被当成目的地**：「X 日游」前直接截最多 4 个字，不区分「从 A 出发去 B」；
   同时 `matchCity` 对整句做包含匹配，句子里先出现的出发地会先命中。
2. **句尾助词/动词被吞进城市名**：「成都吃四天」「张家界的」「雪乡的」「泉州的」都是
   截取后没有剥「的/吃/玩/发去」这类残留。
3. **脏名字污染全局目录**：这些串被拿去 `search_city_online`，模型会顺着名字编出一整套
   「城市目录」，再经 `register` 进入内存城市表；run-18 的失败文案里
   「本地已开通：…成都吃、发去厦门、青岛、张家界的、大理丽江、雪乡的、泉州的」
   就是污染后的结果。
4. **规则槽位压过模型槽位**：`IntentAgent` 里 `RequirementSlots.fromMessage(message)`
   优先于模型返回的 `slots`，规则抽错时模型也救不回来。

### 2.2 修复

- `CityNames.stripOriginClause`：先剥「从 A 出发去/到 B」「A 出发的 B」，目的地不再取出发地；
  `TravelCatalogService.matchCity` 也先剥再匹配。
- `MessageParser.guessCityName`：目的地动词（去/到/前往…）→ 已知城市词表（`CityAtlas` 98 个城市）
  → 剥天数/助词/动词 → 2-4 字启发式；猜不出返回 `null` 交追问，不再编城市。
  两个真城市粘在一起（「扬州镇江」「大理丽江」）取第一个——单次只能规划一个城市，
  但绝不产出查无此城的串。
- `CityNames.isPlausibleName` + `TravelCatalogService.register` + `OnlineCityService.getOrLoad/accept`
  三重校验：脏名字不联网生成、不注册、不进入匹配候选，历史文件缓存里的脏名字也丢弃。
- `IntentAgent` / `PlannerAgent` 对模型给的 `destination` 做同样校验；`guessRequestedCity`
  复用同一套抽取，不再在失败文案里出现「州镇江的」。

### 2.3 修复前后逐轮对照（只列发生变化的 13 轮）

| # | 消息（截断） | 修复前 | 修复后 |
| --- | --- | --- | --- |
| 2 | 北京出发去西安五日游 | 北京（出发地） | **西安** |
| 3 | 想去成都吃四天 | 成都吃 | **成都** |
| 4 | 深圳出发去厦门三日游 | 发去厦门 | **厦门** |
| 7 | 从成都出发去重庆三日游 | 成都吃 | **重庆** |
| 8 | 杭州出发去黄山四日游 | 杭州（出发地） | **黄山** |
| 11 | 上海出发去苏州一日游 | 上海（出发地） | **苏州** |
| 12 | 北京出发去承德避暑山庄 | 北京（出发地） | **承德** |
| 13 | 从长沙出发去张家界五日游 | 张家界的 | **张家界** |
| 14 | 想去大理丽江玩五天 | 大理丽江 | **大理**（多城市取第一个） |
| 15 | 哈尔滨出发去雪乡三日游 | 雪乡的 | **雪乡** |
| 16 | 厦门出发去泉州两日游 | 泉州的 | **泉州** |
| 18 | 从南京出发去扬州镇江两日游 | 州镇江的 → 失败 | **扬州**（多城市取第一个） |
| 20 | 想去舟山群岛玩三天 | 舟山群岛 | **舟山** |

run-19「营口鲅鱼圈」规则层识别不出单城市，返回 `null` 交给模型槽位，最终按「营口」进入
「还缺同行人数」追问；不再出现「营口鲅鱼」这种半截目的地。

### 2.4 测试

- 新增 `MessageParserCityTest`：20 条压测消息逐条断言目的地、出发地不误取、多城市取第一个、
  结果必须通过 `CityNames.isPlausibleName`，并保留旧句式（N 日游 / 去 X 两天 / X 行程）。
- `CityNamesTest` 增 `isPlausibleName` / `stripOriginClause` 用例；
  `CityMatchTest` 增「北京出发去西安不能匹配成北京」与「脏名字不注册」用例。
- 后端全量：`mvn -B test` **648 用例，0 失败**（本地，2026-11-24）。

## 3. 限流复验

### 3.1 修复前

compose 默认 `RATE_LIMIT_TRUSTED_PROXIES=172.16.0.0/12`，nginx 又把
`X-Forwarded-For` 覆写为 `$remote_addr`。后果：来源落在该私网段的客户端被后端当成
「受信代理跳」跳过，XFF 耗尽后退回 nginx 容器 IP，所有请求塌成一个桶；
一个滥用者可锁死其他人，本机测出的 429 也不能证明按真实客户端隔离。

### 3.2 修复

- compose 默认网络固定为 `172.31.240.0/24`，web（nginx）固定 `172.31.240.10`；
- `RATE_LIMIT_TRUSTED_PROXIES` 默认收窄为 `172.31.240.10/32`，只信 nginx 自己；
- `.env.production.example` / `production-config.md` / `deployment.md` 同步口径：
  **填反代自己的确切地址，禁止填整个私网段**。

### 3.3 容器内实测（2026-11-24）

四个来源，每个连续 12 次错误密码登录（auth 限额 10/300s）：

| 来源 | 真实源 IP | 结果（逐次） | Redis 计数键身份 |
| --- | --- | --- | --- |
| A：后端容器 | `172.31.240.4` | `401`×10 → `429`×2 | `ip:172.31.240.4` |
| B：临时容器 | `172.31.240.20` | `401`×10 → `429`×2 | `ip:172.31.240.20` |
| C：宿主机经 nginx | `172.31.240.1` | `401`×10 → `429`×2 | `ip:172.31.240.1` |
| D：临时容器 + 伪造 `X-Forwarded-For: 203.0.113.9` | `172.31.240.21` | `401`×10 → `429`×2 | `ip:172.31.240.21` |

- A/B 与 D 同处 compose 网段，**没有**被当成受信跳并进 nginx 的桶；
- D 伪造的 `203.0.113.9` 在 Redis 中没有产生计数键——nginx 覆写 XFF，后端也不信任它；
- 完整命令与输出：`.evidence/rate-limit-e2e.log`（脚本 `.evidence/rate-limit-e2e.sh`，本地复现用）。

单元测试 `RateLimitTrustedProxyTest` 保留原有「最右非受信跳 / 全受信回退 / 非受信来源忽略 XFF」
三组覆盖，并新增「同网段来源不再被当受信跳」用例；4 用例通过。

**边界**：本机只能造出私网源 IP，没有公网客户端；本测试证明的是「受信代理 = 确切 nginx 地址」
这一机制（公网部署时 nginx 的 `$remote_addr` 就是真实公网 IP，自然分桶）。
同一宿主机直连 nginx 的客户端仍共享网关 IP（`172.31.240.1`），这是 Docker 网络语义，不是限流缺陷。

## 4. S3

### 4.1 验证边界（先把话说清楚）

本机拉不到 MinIO / S3Mock 镜像（Docker Hub 直连 `000`、1panel 对 `minio/minio` 403、
`adobe/s3mock` 层下载完但镜像落不下来、1ms 429），也没有真实云厂商的 endpoint/凭证。
因此本节用**本地 S3 协议桩**（`.evidence/s3-stub.mjs`，path-style，支持 PUT/HEAD/GET/DELETE/List，
忽略签名）验证 `S3PhotoStore` + AWS SDK 的完整 HTTP 链路。

**这不是「真实 MinIO/AWS 已验」**：签名/鉴权、HTTPS、版本控制/生命周期、分片上传、
多实例并发仍未验证；真实供应商仍需一次带凭证的验收。

### 4.2 验证中发现并修掉的两个兼容性问题

1. **缺少 path-style 开关**：AWS SDK 默认 virtual-hosted-style，MinIO 这类自定义 endpoint
   会请求 `bucket.127.0.0.1`，DNS 直接失败。新增
   `travel.photos.s3.path-style` / `TRAVEL_PHOTOS_S3_PATH_STYLE`（默认 false）。
2. **默认尾校验把兼容网关的成功上传判成失败**：SDK 2.25 默认对 PutObject 做 MD5/CRC32
   尾校验，不返回校验头的网关会抛 `ArrayIndexOutOfBoundsException`（Base16Codec）。
   新增 `travel.photos.s3.checksum-validation` / `TRAVEL_PHOTOS_S3_CHECKSUM_VALIDATION`
   （默认 **true**，保持 AWS/MinIO 完整性校验；只有不返回校验头的网关才显式关掉）。

### 4.3 实测结果（本地协议桩 + `PHOTOS_STORE=s3`）

| 步骤 | 结果 |
| --- | --- |
| 上传 `POST /api/trips/{id}/photos` | **200**，对象键 `5a3cc76a355f43a7bc46f5ac13fceff7.png`，68 B，`contentType=image/png` |
| 对象存储侧清单 | 1 个对象，大小与上传一致 |
| 读回 `GET /api/photos/{id}/raw` | **200**，sha256 与上传文件**一致** |
| 删除照片 `DELETE /api/photos/{id}` | **200**；对象清单变空，raw 再读 **404** |
| 删除行程 `DELETE /api/trips/{id}` | **200** |

证据：`.evidence/s3-e2e.log`；复现脚本 `.evidence/s3-e2e.sh` + `.evidence/s3-stub.mjs`（本地，不随仓库提交）。

### 4.4 仍未验证

- 真实 MinIO / AWS S3 / 阿里 OSS / 腾讯 COS 的 endpoint + 凭证 + HTTPS；
- 供应商侧的版本控制、生命周期、跨区复制与备份中的历史对象删除（见
  [data-deletion.md](../legal/data-deletion.md) §4）；
- 大文件/分片上传、多实例并发写同一 bucket。

## 5. 仍未验证 / 外部阻塞

- **真机**：本机没有 Android/iOS 真机通道；Playwright WebKit/Chromium 预设不等于真机，
  不写「已通过」。按 [上线执行清单 §5.1](../operations/launch-checklist.md) 人工跑。
- **法务**：隐私政策/第三方服务/删除流程/许可证仍需法务确认，工程侧只能保证文档与代码一致。
- **高德 MCP 真实 Key**：`AMAP_MAPS_API_KEY` 为空，MCP 侧 10021 退避与降级告警仍只有 REST 证据。
- **H2 console「404」口径**：实际后端 401、nginx 200 SPA；要么改验收口径，要么给安全链加显式 404。
- **S3**：见 §4。

## 附录：复现命令

```bash
# 1) 旧证据重分类（不调用模型）
node .e2e/llm-load-20.mjs --classify

# 2) 20 连真实模型重跑（需要目标后端已配置真实 LLM_API_KEY）
E2E_API_TARGET=http://127.0.0.1:18081 \
LOAD_OUT=.evidence/llm-load-20-cityfix \
node .e2e/llm-load-20.mjs

# 3) 限流容器复验（compose 已启动；会清理四个来源的计数键）
bash .evidence/rate-limit-e2e.sh
```

> 说明：`.evidence/` 在 `.gitignore` 内，原始响应与日志不随仓库提交；本文只保留可复核的结论与命令。