# 第三方服务与数据处理说明

> 状态：**当前事实（合同/条款状态待确认）** | 最后核对：2026-11-22 | 事实来源：`application.yml`、`travel-agent-server/src/main/java/com/travel/agent/{llm,rag,service,mcp,security}/`、`travel-agent-web/src/api/`、`docker-compose.yml`、[外部依赖降级矩阵](../guide/external-dependencies.md)、[高德 MCP 接入](../guide/mcp-amap.md)

本文列出系统会访问的外部服务、**出站内容**、用途、密钥归属与降级行为，供隐私评审、采购与运维核对。具体服务条款、数据处理协议（DPA）、数据存放地域与供应商保留期**均未在本仓库确认**，上线前必须逐项与供应商核对并补进本文。

> 通用原则：调用方不主动发送用户名、密码、Cookie、JWT；但用户自己输入到行程/对话里的信息（姓名、地址、同行人、联系方式等）会随业务文本进入第三方。产品侧应提示用户不要输入与行程无关的敏感信息。

## 1. 总表

| 服务 / 处理方 | 用途 | 出站数据 | 密钥归属 | 开关与配置 | 失败/未配置时 | 官方入口 |
| --- | --- | --- | --- | --- | --- | --- |
| DeepSeek 或任意 OpenAI-compatible LLM | 意图识别、结构化规划、文案润色、回顾总结 | 用户消息、最近会话历史（默认 10 条）、行程摘要、工具观察摘要、必要的 RAG 片段 | 运营方自己的 Key | `LLM_ENABLED`、`LLM_BASE_URL`、`LLM_API_KEY`、`LLM_MODEL` | 全链路降级规则引擎；`llm.calls{result=skipped/failed}` | <https://api.deepseek.com> |
| Embedding 供应商（默认 SiliconFlow，默认未启用） | 知识库向量化与语义检索 | 知识库正文、检索查询文本 | 运营方自己的 Key | `EMBEDDING_ENABLED`（默认 false）、`EMBEDDING_BASE_URL`、`EMBEDDING_API_KEY`、`EMBEDDING_MODEL` | 本地字符 bigram 哈希向量（进程内锁定，重启才恢复） | <https://api.siliconflow.cn> |
| 高德 Web 服务（REST） | 地理编码、POI 搜索、路线规划 | 城市/地址文本、POI 关键词、起终点坐标 | 运营方「Web 服务」Key | `AMAP_KEY` | 定位退 OSM；路线 `estimated`；`amapConfigured=false` | <https://restapi.amap.com> / <https://console.amap.com> |
| 高德 MCP server（本机 npx 子进程） | 把 12 个 `maps_*` 工具注册给模型 | 与 REST 相同的地址/坐标/关键词 | 运营方「Web 服务」Key | `MCP_ENABLED=true` + `AMAP_MAPS_API_KEY` | 单个 server 接入失败只 WARN 跳过；无 `mcp_amap_*` 工具 | 包名 `@amap/amap-maps-mcp-server` |
| 高德 Web 端 JS API（浏览器） | 行程/足迹地图渲染与瓦片 | 浏览器 IP、视野范围、标记坐标；前端 Key 随产物公开 | 运营方「Web 端(JS API)」Key | `VITE_AMAP_KEY`、`VITE_AMAP_SECURITY_CODE`、`VITE_FOOTPRINT_BASEMAP` | 地图不渲染/回退 OSM 底图（足迹）；行程时间轴仍可用 | <https://console.amap.com> |
| Open-Meteo | 真实天气预报 | 城市名或坐标 | 无 Key（免费） | 默认启用（`WEATHER_SIMULATION=false`）；模拟开关仅测试/离线演示 | 不可用即明确拒绝依赖天气的修改（不降级模拟） | <https://open-meteo.com> |
| S3 兼容对象存储（可选） | 照片多实例共享存储 | 照片文件与对象键 | 运营方对象存储密钥 | `PHOTOS_STORE=s3` + `.env` 的 `S3_*`（compose 映射为 `TRAVEL_PHOTOS_S3_*`） | 配置不全**启动失败**；运行期 500/404 按错误语义返回 | 以所选供应商为准（AWS S3 / MinIO / 阿里 OSS / 腾讯 COS） |
| Redis（compose 内置，或外部托管） | 多实例共享限流计数 | 限流键（含用户名或 IP）、窗口 TTL | 运营方自己的实例 | `RATE_LIMIT_STORE=redis`（compose 默认）+ `SPRING_DATA_REDIS_*` | **fail-open 放行**并告警；限流暂时失效；启动期 Redis healthcheck 失败会阻止 backend 启动 | <https://redis.io> |
| Telegram（可选） | 渠道入口 | 聊天消息文本、Telegram 用户标识 | 运营方 Bot Token | `TELEGRAM_BOT_TOKEN` | 渠道关闭 | <https://telegram.org> |
| 微信公众号（可选） | 渠道入口 | 用户消息、OpenID、回复文本 | 运营方公众号配置 | `WECHAT_OA_*` | 渠道关闭 | <https://mp.weixin.qq.com> |
| GitHub（代码托管 / Actions） | 代码托管、CI、依赖与安全扫描 | 源代码、CI 日志；当前 workflow 未引用生产密钥 | 仓库所有者 | 仓库设置 | CI 不通过；不影响运行时 | <https://github.com> |
| OSM Nominatim（兜底） | 未配置高德时的地理编码兜底 | 城市/地址文本 | 无 Key | 自动（无开关） | 定位失败则坐标缺失，规划走估算 | <https://www.openstreetmap.org> |

## 2. LLM（OpenAI-compatible）

- **端点**：`LLM_BASE_URL`，默认 `https://api.deepseek.com/v1`；可替换为任意兼容端点（此时**处理方随之改变**，必须重新做隐私与合同评估）。
- **出站内容**：用户在对话里输入的消息；`CONVERSATION_HISTORY_LIMIT`（默认 10 条消息）以内的历史；意图识别/规划/审核/润色所需的行程摘要与工具观察；RAG 检索到的知识片段与引用。按代码检索，提示词构造不使用 `username`/`userId` 字段。
- **不发送**：密码、登录 Cookie/JWT、数据库连接串、其他用户的行程。
- **密钥归属**：运营方在供应商控制台申请，只通过 `LLM_API_KEY` 注入。
- **超时/重试**：连接 5s / 读 30s（可配），**不重试**；受 Agent 端到端预算（默认 30s）封顶。
- **降级**：未配置或调用失败返回 `null`，上层用规则模板继续回复；`llm.calls{result}` 区分 `ok/failed/skipped/quota`，`/api/system/status` 的 `llmVerified` 反映最近一次真实调用。
- **待确认**：供应商数据保留期、是否用于训练、数据存放地域、DPA、计费与限流条款。

## 3. Embedding（默认关闭）

- **默认状态**：`EMBEDDING_ENABLED=false`，知识库使用本地字符 bigram 哈希向量，**不出站**。
- **开启后**：把知识库正文与检索查询发送到 `EMBEDDING_BASE_URL`（默认 SiliconFlow）。
- **降级**：失败后**进程内锁定**本地哈希，需重启才重新尝试；切换模型必须清空 `rag_document`/`rag_chunk`/`rag_document_version` 重建（否则新旧向量不可比）。
- **待确认**：供应商保留期、训练使用条款、地域、模型许可。

## 4. 高德（REST / MCP / JS API）

- **两个后端 Key 不是一回事**：`AMAP_KEY` 供 REST（地理编码/POI/路线）；`AMAP_MAPS_API_KEY` 供 MCP 子进程。两者都必须是「**Web 服务**」类型，不要复用前端 JS Key。
- **前端 Key**：`VITE_AMAP_KEY` + `VITE_AMAP_SECURITY_CODE` 是「Web 端(JS API)」类型，构建时写进前端产物，属**公开标识**；按域名白名单限制，不当作机密。
- **出站内容**：城市/地址文本、POI 关键词、坐标；浏览器侧还包括 IP、地图视野与标记。
- **降级**：REST 失败时定位退 OSM Nominatim、路线退本地 haversine 估算（`routeProvider=estimated`）；MCP 失败只影响 `mcp_*` 工具。
- **QPS**：只在 infocode `10021`（QPS 超限）时做一次 220–400ms 退避重试；其他错误不重试。
- **待确认**：高德开放平台服务条款、配额与商用授权；**瓦片商用授权**（足迹底图）需单独确认；MCP 包的许可证与再分发条款。

## 5. Open-Meteo

- **出站内容**：城市名（地理编码）与坐标（预报）。
- **开关**：默认启用（免费、无需 Key）；`WEATHER_SIMULATION=true` 只在测试/离线演示时阻断外呼并返回模拟数据。
- **失败语义**：网络失败或超出 16 天预报窗口即「不可用」——依赖天气的修改被明确拒绝，
  普通规划如实标注「未做天气校验」，**不降级模拟**（不把假天气当事实）。
- **待确认**：免费服务的非商用条款与调用频率限制；商用部署前确认许可。

## 6. S3 兼容对象存储（可选）

- **触发**：`PHOTOS_STORE=s3`；`.env` 填 `S3_ENDPOINT/S3_REGION/S3_BUCKET/S3_ACCESS_KEY/S3_SECRET_KEY`，compose 映射为 `TRAVEL_PHOTOS_S3_*`；`max-retries` / `api-call-timeout-ms` 等细项才需 `SPRING_APPLICATION_JSON`。
- **出站内容**：照片二进制与对象键（扁平 UUID + 扩展名，**不含用户/行程前缀**）。
- **权限建议**：只授予目标 bucket 的 Put/Get/Head/Delete；开启版本控制与生命周期；禁止公共读。
- **失败语义**：配置缺失启动失败；写失败 500、读 404/500，均向用户明示，不静默丢文件。
- **待确认**：对象存储供应商的加密、地域、日志与 DPA。

## 7. Redis（可选）

- **出站内容**：限流计数键 `ratelimit:<bucket>|<identity>|...`，identity 为用户名或 IP；TTL 为窗口长度（默认 300 秒）。不存业务正文。
- **故障行为**：连接失败或命令超时 → fail-open 放行 + WARN + `ratelimit_failopen_total` + `external.degraded{provider="redis"}`；这意味着限流暂时失效，必须告警。
- **待确认**：若使用托管 Redis，需确认其数据地域与备份策略。

## 8. 渠道（Telegram / 微信公众号，默认关闭）

- **Telegram**：长轮询纯出站，无需公网回调；发送聊天文本与 Telegram 用户标识。
- **微信公众号**：需要公网回调 URL；被动回复 5 秒窗口，规划类请求可能超窗，正式环境建议配置客服消息。
- **待确认**：平台条款、消息内容保留期、用户告知方式。

## 9. CI 与代码托管

- GitHub Actions 运行构建、测试、CodeQL、依赖扫描；当前 workflow 未引用生产密钥（MySQL service 使用作业内固定口令）。
- 若未来在 Actions 中配置部署密钥，必须使用 Environment secrets 并限制可触发分支；不得把生产 `.env` 作为 artifact 上传。
- **待确认**：仓库可见性、组织级安全策略、Actions 日志保留期。

## 10. 账号注销与第三方数据

账号注销 API（`DELETE /api/auth/me`）已实现，但**第三方侧的数据不会随本仓库的删除事务自动清除**，需要按处理方分别理解：

| 处理方 | 注销时的行为 | 仍需处理的边界 |
| --- | --- | --- |
| LLM（DeepSeek 等） | 提示词/消息按请求发送；本项目**不**在供应商侧创建用户级数据副本，也没有供应商侧的删除 API 调用 | 供应商是否保留请求日志、用于训练或审计，由其条款/DPA 决定；注销**不能撤回已经发送的请求**。需在合同中确认删除/保留机制 |
| Embedding 供应商 | 与 LLM 相同：按请求发送知识库/查询文本；本项目不保存供应商侧副本 | 同上；开启 Embedding 前确认保留期与训练条款 |
| 高德 REST / MCP | 地址/坐标/POI 关键词按请求发送，本项目不在高德侧建用户数据 | 高德的访问日志保留由平台条款决定 |
| S3 兼容对象存储 | 账号注销在数据库事务提交后，按 `trip_photo.stored_name` 逐个删除对象（`PhotoStore.deleteQuietly`） | **版本控制、跨区复制、生命周期归档、对象存储备份中的历史对象不会自动删除**；需配置生命周期/版本清理，并在隐私政策说明 |
| Redis | 限流键含用户名/IP，TTL 默认 300 秒；注销不主动删除 | 要求立即清除时由运维 `SCAN`/`DEL` 精确处理（不要 `FLUSHALL`） |
| Telegram / 微信公众号 | 渠道消息由平台保存；本项目只做业务处理 | 平台侧记录需按平台条款与用户操作处理 |
| 备份（mysqldump / 对象存储快照） | 注销不追溯清理备份 | 备份保留周期内仍可能有历史数据，到期自然消失；需在隐私政策说明 |

> 结论：本仓库能保证的是**自身数据库与已接入照片存储的对象删除**；第三方侧的删除能力取决于供应商条款与运维配置。上线前需把上表逐项写入合同/DPA 与隐私政策，不能笼统宣称“注销即全部第三方数据删除”。

## 11. 供应商变更流程

新增或替换任何第三方服务，发布前必须：

1. 更新本文总表与 [隐私政策](privacy-policy.md) 的数据类别/处理方；
2. 由法务确认条款、数据地域、保留期与跨境机制；
3. 在 [生产配置清单](../operations/production-config.md) 登记新变量、密钥权限与轮换方式；
4. 确认降级行为与可观测指标（失败不能静默）；
5. 更新 [外部依赖降级矩阵](../guide/external-dependencies.md) 与对应测试。

相关文档：[隐私政策](privacy-policy.md) · [数据删除流程](data-deletion.md) · [生产配置清单](../operations/production-config.md) · [外部依赖降级矩阵](../guide/external-dependencies.md) · [高德 MCP 接入](../guide/mcp-amap.md)