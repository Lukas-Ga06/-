# 生产配置清单与模板（P0-A）

> 状态：当前事实 | 适用版本：0.1.0（Spring Boot 3.5.12 / MySQL 8.4 / Node 20） | 最后验证：2026-11-22 | 事实来源：`travel-agent-server/src/main/resources/application.yml`、`application-mysql.yml`、`docker-compose.yml`、`travel-agent-server/Dockerfile`、`travel-agent-web/Dockerfile`、`travel-agent-web/nginx.conf`、`SecurityConfig.java`、`SystemController.java`、`.env.production.example`

本文是 P0-A 的配置面交付：把生产必须显式设定的变量、每个密钥的权限/配额/轮换方式、未配置时的**预期行为**、以及容器部署的真实透传边界写成一份可审计清单。模板在仓库根 [`.env.production.example`](../../.env.production.example)。

> **先看结论**
>
> - 配置清单与模板**已就绪**；但**没有**用一份全新生产 `.env` 在真实环境启动验证过（本文所有“行为”均来自源码与既有文档，不来自新的生产演练）。
> - `LLM_MODEL` 生产值取 `deepseek-flash`：2026-11-22 用当前 Key 核对 `/models`，可用清单为 `deepseek-flash`、`deepseek-v4-pro`；`application.yml` 与 `docker-compose.yml` 默认均为 `deepseek-flash`。换 provider / 换 Key 必须重新核对。
> - `/api/system/status` 已返回 `llmProvider`、`llmModelAvailable`（含 `null=未校验`）、`llmModelError`、`llmModelCheckPending`、`mcpEnabled`、`mcpConnected`、`mcpToolCount`、`mcpServers`、`mcpLastError`、`mcpHint`、`amapMcpToolCount`（P0-A，`SystemController` 已实现）。判读口径见 §4。
> - 容器部署已基本补齐透传：应用侧 55 个变量中 **50 个**由 compose 直接透传；只剩 `TELEGRAM_POLL_TIMEOUT` 与 4 个由 compose 字面量固定的 `MYSQL_HOST/PORT/DATABASE/USERNAME` 不经过 `.env`。S3 使用 `S3_*` 映射为 `TRAVEL_PHOTOS_S3_*`；Redis 由 compose 内置服务提供。见 §5。

---

## 1. 使用方式

```bash
# 1) 从模板生成 .env（仓库根目录；.env 不要提交）
cp .env.production.example .env

# 2) 生成并写入 JWT_SECRET（Linux / macOS / Git Bash）
sed -i "s|^JWT_SECRET=.*|JWT_SECRET=$(openssl rand -base64 48)|" .env

# 3) 渲染检查：缺 JWT_SECRET 会直接报 “JWT_SECRET is required”
docker compose config

# 4) 启动
docker compose up -d --build
```

Windows PowerShell 生成密钥的方式（不使用 `Get-Random` 充当密钥）见 [部署与运维 §3.1](../guide/deployment.md)。启动后按 [部署与运维 §3.4](../guide/deployment.md) 的 10 项验证清单检查，并额外核对本文 §6 的自检项。

---

## 2. 生产变量清单

> 读法：**必填** = 缺失会导致启动失败、或虽能启动但生产不可接受（必须显式设定）。**默认** = `application.yml` / `application-mysql.yml` 的 `${VAR:default}`；compose 覆盖值单独注明。完整变量表与来源统计以 [部署与运维 §5](../guide/deployment.md) 为权威（现为应用侧 55 + 前端构建期 3 + 仅 Docker 面 4 + S3 映射 5 = **67**），本文只列生产决策相关的变量；compose 实际透传核对结果见 §5。

### 2.1 认证与安全

| 变量 | 必填 | 默认 | 生产示例 | 密钥 | 说明 |
| --- | --- | --- | --- | --- | --- |
| `JWT_SECRET` | **是** | 空 | `<openssl rand -base64 48>` | **是** | HS256 签名密钥，Base64 解码后 ≥ 32 字节；非法/过短**启动失败**；compose 缺失直接拒绝启动 |
| `JWT_EXPIRE_HOURS` | 否 | `72` | `72` | 否 | token 有效期；与 httpOnly Cookie `maxAge` 对齐 |
| `TICKET_TTL_SECONDS` | 否 | `300` | `300` | 否 | 照片原图 / SSE 短期只读票据 TTL；票据会出现在 URL 里 |
| `COOKIE_SECURE` | **是（HTTPS）** | `false` | `true` | 否 | 登录 Cookie 是否带 `Secure`；HTTPS 下设 `false` 属配置错误，HTTP 下设 `true` 会“登录成功但立刻 401” |
| `DEMO_ACCOUNT` | **是（生产 false）** | yml `true` / compose `false` | `false` | 否 | 仅控制启动时是否创建 `demo/demo123`；不会删除已存在的 demo 账号 |
| `H2_CONSOLE_ENABLED` | **是（生产 false）** | `false` | `false` | 否 | `true` 会把 `/h2-console/**` 加进**免认证白名单**（`SecurityConfig`）；生产必须关闭 |
| `CORS_ALLOWED_ORIGINS` | **是（收敛域名）** | 本地 5173/4173 两套 | `https://your-domain.example.com` | 否 | 逗号分隔；带 `allowCredentials=true`，**不能填 `*`**（Spring 会拒绝） |
| `RATE_LIMIT_ENABLED` | **是（保持 true）** | `true` | `true` | 否 | 限流总开关；关掉等于放弃登录爆破/刷模型保护 |
| `RATE_LIMIT_TRUSTED_PROXIES` | 反代部署必填 | 空 | `172.16.0.0/12`（compose 默认） | 否 | 只信受信代理；默认空 = 不读 `X-Forwarded-For`，反代后所有请求会按代理 IP 共用桶 |
| `MYSQL_ROOT_PASSWORD` | **是（首次启动前）** | `travel_root_pw` | `<强随机口令>` | **是** | 仅 compose 面变量；同时用于 `mysqladmin ping` 健康检查，数据卷初始化后改值会导致 unhealthy |
| `MYSQL_PASSWORD` | **是（首次启动前）** | yml `root` / compose `travel_pw` | `<强随机口令>` | **是** | 业务账号密码；初始化后再改不会修改既有账号 |

### 2.2 限流细项

| 变量 | 必填 | 默认 | 生产示例 | 密钥 | 说明 |
| --- | --- | --- | --- | --- | --- |
| `RATE_LIMIT_AUTH` | 否 | `10` | `10` | 否 | 登录/注册：同 IP 每窗口上限 |
| `RATE_LIMIT_SESSION` | 否 | `60` | `60` | 否 | `GET /api/auth/me` 独立桶，避免刷新页面被登录桶踢成 429 |
| `RATE_LIMIT_AGENT` | 否 | `30` | `30` | 否 | `/api/agent/**` 同用户上限，**直接决定模型花费** |
| `RATE_LIMIT_UPLOAD` | 否 | `60` | `60` | 否 | 照片上传同用户上限 |
| `RATE_LIMIT_DEFAULT` | 否 | `600` | `600` | 否 | 其它 `/api/**` 同用户上限 |
| `RATE_LIMIT_WINDOW_SECONDS` | 否 | `300` | `300` | 否 | 固定窗口长度 |
| `RATE_LIMIT_STORE` | 多实例必填 | yml `memory` / compose `redis` | 单实例 `memory` / 多实例 `redis`（compose 默认） | 否 | compose 内置 `redis` 服务并通过 `SPRING_DATA_REDIS_HOST/PORT` 注入；Redis 运行期故障时 **fail-open 放行**并告警。Redis 容器不健康会因 `depends_on` 阻止 backend 启动 |

### 2.3 LLM 与 Embedding

| 变量 | 必填 | 默认 | 生产示例 | 密钥 | 说明 |
| --- | --- | --- | --- | --- | --- |
| `LLM_ENABLED` | 否 | `true` | `true` | 否 | 模型总开关；`false` 等同显式走规则引擎 |
| `LLM_BASE_URL` | 否 | `https://api.deepseek.com/v1` | 同默认或自建网关 | 否 | 任意 OpenAI-compatible 端点；换端点 = 换数据处理方，需重新评估 |
| `LLM_API_KEY` | **是（要 AI 能力）** | 空 | `<供应商 Key>` | **是** | 未配置时 `llm.calls{result=skipped}`、`llmConfigured=false`，回复来自规则模板 |
| `LLM_MODEL` | **是（显式锁定）** | `deepseek-flash` | `deepseek-flash` | 否 | 当前 Key 下可用；`application.yml` 与 `docker-compose.yml` 默认均已对齐；见 §2.4 |
| `LLM_MODEL_PROBE_ENABLED` | 否 | `true` | `true` | 否 | 启动后异步探测 `/models`（≤3s），结论进 `llmModelAvailable`；关闭后该字段为 `null`（未校验）；compose 已透传 |
| `LLM_TEMPERATURE` | 否 | `0.4` | `0.4` | 否 | 采样温度 |
| `LLM_CONNECT_TIMEOUT_MS` | 否 | `5000` | `5000` | 否 | 建连超时 |
| `LLM_READ_TIMEOUT_MS` | 否 | `30000` | `30000` | 否 | 读超时；另受 Agent 端到端预算封顶 |
| `LLM_DAILY_QUOTA` | **是（生产正值）** | `0`（不限） | `200` | 否 | 每用户每日调用上限，计数落库 `llm_quota_usage`；超限降级并记 `llm.calls{result=quota}` |
| `EMBEDDING_ENABLED` | 否 | `false` | `false`（显式降级） | 否 | 开启后失败会**进程内锁定**降级本地哈希，恢复需重启；切模型需重建知识库 |
| `EMBEDDING_BASE_URL` | 否 | `https://api.siliconflow.cn/v1` | 同默认 | 否 | Embedding 端点 |
| `EMBEDDING_API_KEY` | 开启 Embedding 时是 | 空 | `<供应商 Key>` | **是** | 缺失时若 `EMBEDDING_ENABLED=true` 会调用失败并锁定降级 |
| `EMBEDDING_MODEL` | 否 | `BAAI/bge-m3` | `BAAI/bge-m3` | 否 | 换模型必须清空 `rag_document`/`rag_chunk`/`rag_document_version` 重建 |
| `RAG_MIN_SCORE` | 否 | `0.05` | `0.05` | 否 | 问答最低相似度；只影响问答，不影响检索指标 |

#### 2.4 `LLM_MODEL` 的当前事实

| 项 | 事实 | 证据 |
| --- | --- | --- |
| 应用默认 | `deepseek-flash`（`application.yml` `travel.llm.model`，P0-A 已改为当前 Key 下可用值） | `application.yml` |
| compose 默认 | `deepseek-flash`（已与 yml 对齐） | `docker-compose.yml` |
| 当前 Key 的可用模型 | `deepseek-flash`、`deepseek-v4-pro`（2026-11-22 用 `/models` 核对） | [路线图](../PROJECT_ROADMAP.md) P0-A；本批交付记录 |
| 启动探测 | 应用就绪后异步 `GET {LLM_BASE_URL}/models`（≤3s），结果写入 `/api/system/status`：`llmModelAvailable`（true/false/**null=未校验**）、`llmModelError`、`llmModelCheckPending` | `LlmModelProbe`、`SystemController` |
| 模型不存在时 | 启动**不失败**；WARN 并列出可用模型；`llmModelAvailable=false`，真实调用失败后 `llmVerified=false`、`llm.calls{result=failed}`，回复退回规则模板 | `LlmModelProbe`、`DelegatingLlmClient`、`LlmRuntimeStatus` |
| 未配置 Key / 关闭探测 / 探测请求失败 | `llmModelAvailable=null`（**未校验**），不能当成可用 | `LlmModelProbe.ModelCheck` |

核对命令（不要提交 Key；输出只看 `data[].id`）：

```bash
curl -sS -H "Authorization: Bearer $LLM_API_KEY" "$LLM_BASE_URL/models"
```

### 2.5 高德、MCP 与天气

| 变量 | 必填 | 默认 | 生产示例 | 密钥 | 说明 |
| --- | --- | --- | --- | --- | --- |
| `AMAP_KEY` | **是（要真实地图）** | 空 | `<Web 服务 Key>` | **是** | 后端高德 **「Web 服务」** 类型 Key（地理编码/POI/路线）；**不能**复用前端 JS API Key |
| `AMAP_MAPS_API_KEY` | 启用 MCP 时是 | 空 | `<Web 服务 Key>` | **是** | 高德 MCP server 子进程专用，变量名单数 `KEY`；与 `AMAP_KEY` 不是一个值 |
| `MCP_ENABLED` | 否 | `false` | `true` | 否 | 开启后用 `npx @amap/amap-maps-mcp-server` 注册 12 个只读工具；单个 server 失败只 WARN |
| `VITE_AMAP_KEY` | 行程地图是 | 空 | `<Web 端 JS API Key>` | 否（公开标识） | 构建期注入，出现在前端产物里；与后端 Key 不同 |
| `VITE_AMAP_SECURITY_CODE` | 行程地图是 | 空 | `<安全密钥>` | 否（公开标识） | 与 JS API Key 配套 |
| `VITE_FOOTPRINT_BASEMAP` | 否 | `amap` | `amap` | 否 | 足迹底图；商用前确认高德瓦片授权 |
| `WEATHER_REAL` | 否 | `false` | `true` | 否 | `true` 走 Open-Meteo（免费无 Key）；失败或超 16 天自动降级模拟 |

### 2.6 存储、时区、Agent 与日志

| 变量 | 必填 | 默认 | 生产示例 | 密钥 | 说明 |
| --- | --- | --- | --- | --- | --- |
| `PHOTOS_STORE` | 多实例必填 | `local` | `local` 或 `s3` | 否 | `s3` 需同时配下面的 `S3_BUCKET` / `S3_ACCESS_KEY` / `S3_SECRET_KEY`，缺失**启动失败** |
| `PHOTO_MAX_PER_TRIP` | 否 | `200` | `200` | 否 | 单行程张数上限 |
| `PHOTO_MAX_BYTES_PER_USER` | 否 | `524288000` | `524288000` | 否 | 单用户照片总容量 |
| `S3_ENDPOINT` | 用 S3 时可选 | 空（AWS 默认） | `https://minio.example.com` | 否 | compose 映射为 `TRAVEL_PHOTOS_S3_ENDPOINT`；MinIO/OSS/COS 填各自 endpoint |
| `S3_REGION` | 否 | `us-east-1` | `us-east-1` | 否 | compose 映射为 `TRAVEL_PHOTOS_S3_REGION` |
| `S3_BUCKET` | `PHOTOS_STORE=s3` 时必填 | 空 | `<bucket>` | 否 | compose 映射为 `TRAVEL_PHOTOS_S3_BUCKET` |
| `S3_ACCESS_KEY` | `PHOTOS_STORE=s3` 时必填 | 空 | `<access-key>` | **是** | compose 映射为 `TRAVEL_PHOTOS_S3_ACCESS_KEY`；只授予目标 bucket 的 Put/Get/Head/Delete |
| `S3_SECRET_KEY` | `PHOTOS_STORE=s3` 时必填 | 空 | `<secret-key>` | **是** | compose 映射为 `TRAVEL_PHOTOS_S3_SECRET_KEY` |
| `TZ` | **是** | Dockerfile `Asia/Shanghai` | `Asia/Shanghai` | 否 | 容器时区；compose 已透传，MySQL 另由 compose 固定 |
| `APP_TIMEZONE` | **是** | `Asia/Shanghai` | `Asia/Shanghai` | 否 | 应用时区，钉死 JVM 默认时区；compose 已透传（Dockerfile 另有同值默认） |
| `AGENT_BUDGET_ENABLED` | 否 | `true` | `true` | 否 | 端到端预算开关 |
| `AGENT_BUDGET_TOTAL_MS` | 否 | `30000` | `30000` | 否 | 总预算；耗尽按 ADR-002 降级并标注，不报错 |
| `CONVERSATION_HISTORY_LIMIT` | 否 | `10` | `10` | 否 | 喂给模型的最近消息条数 |
| `CONVERSATION_RETENTION_DAYS` | 否 | `7` | `7` | 否 | 会话消息保留天数 |
| `RAG_CHUNK_VERSION_RETENTION` | 否 | `2` | `2` | 否 | 知识块历史版本保留数；compose 已透传 |
| `JAVA_OPTS` | 否 | 空 | `-Xmx1g -XX:MaxRAMPercentage=70` | 否 | Dockerfile `ENTRYPOINT` 消费；compose 已透传 |
| `SPRING_DATA_REDIS_HOST` / `SPRING_DATA_REDIS_PORT` | 否 | compose `redis` / `6379` | 外部 Redis 地址 | 否 | 仅用于覆盖内置 Redis；外部 Redis 的密码/TLS/database 等通过 override 或 `SPRING_APPLICATION_JSON` 注入 |
| `SPRING_APPLICATION_JSON` | 否 | 空 | 见 §5.2 | 否 | 仅在需要覆盖 compose 未暴露的配置项时使用（如外部 Redis 认证、S3 重试/超时） |

时区口径：`TimeZoneConfig` 启动时把 JVM 默认时区钉为 `travel.timezone` 并打印 `应用时区已固定为 {zone}`；Jackson 另在 `application.yml` 固定 `Asia/Shanghai`。容器默认 UTC，**不设 `TZ`/`APP_TIMEZONE` 会让“今天/明天”、记账、日程整体偏一天**（Dockerfile 已内置这两个值，但自建镜像必须确认）。

日志相关没有独立业务环境变量：`logging.charset.*=UTF-8`、`logging.pattern.level` 带 `traceId` 都在 `application.yml`；输出走容器 stdout，用 `docker compose logs backend` 看。**compose 已为 `backend` / `web` 配置 `json-file` 轮转 `max-size: 10m` / `max-file: 3`**（`mysql` / `redis` 容器未单独配置，日志量小，需要时可补同样配置）。`web` 的 nginx 访问日志已加入自定义 `log_format without_args`（只记 `$uri`、不记查询串），并已修正为 **`http` 上下文（位于 `server {` 之前）**；**仍缺 `nginx -t` / 真实容器验证**。

---

## 3. 密钥权限、配额与轮换

> 原则：每个 Key 只授予该能力所需的最小权限；Key 只放环境变量/密钥管理，不进仓库、不进镜像、不进日志。具体免费额度与控制台数值会变，本文**不写死配额数字**，以供应商控制台/合同为准。

| 密钥 | 变量 | 用途与最小权限 | 配额 / 查看位置 | 轮换步骤 | 轮换影响 |
| --- | --- | --- | --- | --- | --- |
| JWT 签名密钥 | `JWT_SECRET` | 仅本服务签/验登录 token | 无供应商配额；长度 ≥ 32 字节 | 生成新值 → 更新 `.env`/密钥管理 → 重启 backend | 所有已签发 token 立即失效，**全体用户重新登录**；安排在低峰 |
| LLM Key | `LLM_API_KEY` | 仅调用 `/chat/completions`（本服务不用的能力不要授权） | 供应商控制台看余额、限流、用量；本服务另有 `LLM_DAILY_QUOTA` | 控制台新建 Key → 更新环境变量 → 重启 → 验证 `/api/system/status` 的 `llmVerified=true` → 旧 Key 作废 | 轮换窗口内调用失败会降级规则引擎；不丢数据 |
| Embedding Key | `EMBEDDING_API_KEY` | 仅 `/embeddings` | 以控制台为准；本服务无独立配额 | 同 LLM；还需注意模型切换要重建知识库 | 失败会**进程内锁定**本地哈希，恢复需重启 |
| 高德 REST Key | `AMAP_KEY` | 「Web 服务」类型，仅地理编码/POI/路线 | 高德控制台看日配额/QPS；代码只在 `10021` 时退避重试 1 次 | 控制台新建 Key → 更新环境变量 → 重启 → 看 `amapVerified` / `routeProvider` | 失效时定位退 OSM、路线转 `estimated`，不丢数据 |
| 高德 MCP Key | `AMAP_MAPS_API_KEY` | 「Web 服务」类型，供 MCP 子进程 | 同上（与 REST Key 共享账号配额） | 同 REST；改完重启让 `npx` 子进程重新握手 | 失效时 MCP 工具缺失、只 WARN，不影响启动 |
| 前端高德 Key | `VITE_AMAP_KEY` / `VITE_AMAP_SECURITY_CODE` | 「Web 端(JS API)」，按域名白名单 | 高德控制台 | 改构建参数 → **重新构建 web 镜像**（不是重启） | 旧前端产物仍带旧 Key，需重新发布静态资源 |
| MySQL 业务口令 | `MYSQL_PASSWORD` | 仅 `travel_agent` 库 DML/DDL（Flyway 需要 DDL） | 本地实例 | 先 `ALTER USER` 改库内口令 → 同步环境变量 → 滚动重启 backend | 顺序反了会让应用连不上；不要在数据卷初始化后只改 compose 变量 |
| MySQL root 口令 | `MYSQL_ROOT_PASSWORD` | 仅初始化与健康检查 | 本地实例 | 同上；compose 健康检查命令内联了该变量，必须同步 | 不同步会导致 mysql 容器一直 unhealthy |
| S3 访问密钥 | `S3_ACCESS_KEY` / `S3_SECRET_KEY`（compose 映射为 `TRAVEL_PHOTOS_S3_*`） | 仅目标 bucket 的 Put/Get/Head/Delete | 以对象存储控制台为准；建议配生命周期与版本控制 | 新建子账号密钥 → 更新 `.env` 的 `S3_*` → `docker compose up -d` 重建 backend → 上传/读取各验一次 → 停用旧密钥 | 照片读写 500；已有对象不受影响 |
| Webhook 密钥 | `CHANNEL_WEBHOOK_SECRET` | 仅校验 `X-Webhook-Secret` | 无 | 换随机串 → 更新环境变量 → 重启 → 通知调用方同步 | 旧调用方全部 401 |
| 微信公众号 | `WECHAT_OA_TOKEN` / `APPID` / `SECRET` | 公众号服务器配置与客服消息 | 微信公众平台 | 平台重置 → 同步环境变量 → 重启 → 平台侧验证 | 渠道消息失败；不影响主站 |

---

## 4. 未配置或误配关键项时的“意图”

这张表是发布评审的核对依据：**要么明确失败，要么显式降级且可观测**，不允许静默伪装成真实能力。

| 配置状态 | 预期行为（当前代码） | 可观测证据 | 生产是否可接受 |
| --- | --- | --- | --- |
| `JWT_SECRET` 缺失（compose） | `docker compose` 直接拒绝启动：`JWT_SECRET is required` | compose 报错 | **可接受**（fail-fast） |
| `JWT_SECRET` 缺失（非 compose） | 启动生成一次性随机密钥并 WARN；重启后 token 全失效 | 日志 `未配置环境变量 JWT_SECRET` | 仅开发可接受 |
| `JWT_SECRET` 非法 Base64 / 解码 < 32 字节 | **启动失败**（有意 fail-fast） | 启动异常 | **可接受**（fail-fast） |
| MySQL 不可达 / 迁移失败 / `ddl-auto=validate` 不符 | **启动失败** | Flyway/Hibernate 异常 | **可接受**（fail-fast） |
| `LLM_API_KEY` 缺失或 `LLM_ENABLED=false` | 显式降级规则引擎；回复来自模板 | `llmConfigured=false`；`llm.calls{result=skipped}`；日志提示 | 可接受但必须在状态页/日志可见；**不得对外宣称 AI 可用** |
| `LLM_MODEL` 在供应商不存在 | 启动不失败；启动探测 WARN 并列出可用模型；真实调用失败 → 规则降级 | `llmModelAvailable=false`（探测中 `llmModelCheckPending=true`）、`llmModelError`、`llmVerified=false`、`llm.calls{result=failed}` | **不可接受**：发布前必须核对 `/models`；探测只能告警，不能代替修正模型名 |
| `LLM_MODEL_PROBE_ENABLED=false` / 探测请求失败 | 不阻塞启动；状态页保持“未校验” | `llmModelAvailable=null` + `llmModelError` | 可接受（明确未校验），**不得把 null 当成可用** |
| `LLM_DAILY_QUOTA` 超限 | 降级规则引擎；不产生费用 | `llm.calls{result=quota}`；`llmQuotaUsed/llmQuotaLimit` | 可接受（有意的成本保护） |
| `EMBEDDING_ENABLED=true` 但 Key/网络失败 | **进程内锁定**降级本地哈希；本实例不再重试真实端点 | 日志 `真实 Embedding 调用失败，本进程降级为本地哈希`；`external.degraded{provider="embedding"}` | 可接受但必须知悉：恢复需重启 |
| `EMBEDDING_MODEL` 更换但未重建知识库 | 新旧向量混在同一空间、指纹不变、同步跳过 → 检索质量不可比 | 检索命中率异常 | **不可接受**：按 [数据模型 §7.3](../guide/data-model.md) 清空重建 |
| `AMAP_KEY` 缺失 | 定位退 OSM；路线 `estimated`；页面有降级文案 | `amapConfigured=false`、`geoProvider=osm`、`routeProvider=estimated` | 可接受（显式降级），但国内体验差 |
| `AMAP_MAPS_API_KEY` 缺失 + `MCP_ENABLED=true` | MCP 子进程握手失败 → WARN 跳过；无 `mcp_amap_*` 工具 | `mcpEnabled=true`、`mcpConnected=false`、`mcpToolCount=0`、`mcpLastError` 非空、`mcpHint` 说明降级；启动日志 `MCP 服务器 [amap] 接入失败` | 可接受（不影响启动），但状态页必须显示未连接 |
| `PHOTOS_STORE=s3` 但 `S3_BUCKET` / `S3_ACCESS_KEY` / `S3_SECRET_KEY` 缺失 | **启动失败**（`S3PhotoStore` 构造期抛 `IllegalStateException`） | 启动异常 | **可接受**（fail-fast） |
| `RATE_LIMIT_STORE=redis` 但 Redis 不可用 | 启动阶段：compose 的 `depends_on: redis: service_healthy` 让 backend 不启动；运行期 Redis 掉线：**fail-open 放行**，限流暂时失效 | 启动阶段看 `docker compose ps redis` / backend 未起；运行期 `ratelimit_failopen_total` 增长、`external.degraded{provider="redis"}`、WARN 日志 | 启动阶段可接受（fail-fast）；运行期可接受（刻意 fail-open），但必须告警 |
| `COOKIE_SECURE=true` 但实际 HTTP | 登录成功但立刻 401（浏览器不回传 Cookie） | 用户登录循环；响应 Set-Cookie 带 Secure | **不可接受**：部署形态必须与开关一致 |
| `COOKIE_SECURE=false` 但走 HTTPS | Cookie 可经明文链路传输（纵深防御缺口） | 配置核对 | **不可接受** |
| `DEMO_ACCOUNT=true`（生产） | 启动创建 `demo/demo123` 弱密码账号 | 日志 `已创建演示账号` | **不可接受** |
| `H2_CONSOLE_ENABLED=true`（生产） | `/h2-console/**` 进入免认证白名单 | 配置核对 + `docker compose config` | **不可接受** |
| `CORS_ALLOWED_ORIGINS=*` | 与 `allowCredentials=true` 冲突，Spring 拒绝（启动/CORS 处理报错） | 启动或预检异常 | **不可接受** |
| `RATE_LIMIT_ENABLED=false` | 无 429 保护；登录爆破与模型刷量不设防 | `rateLimitEnabled=false` | 仅内网调试可接受 |
| `RATE_LIMIT_TRUSTED_PROXIES` 空但有两层反代 | 所有请求按最内层代理 IP 计数 → 全站共用一个登录桶 | 登录 429 集中出现 | **不可接受**：必须配受信代理网段 |
| `APP_TIMEZONE`/`TZ` 未生效（自建镜像） | “今天/明天”整体偏一天 | `/api/system/status` 的 `timezone`；日志 `应用时区已固定为` | **不可接受** |

> **状态字段口径**：`/api/system/status` 需要登录。当前返回 **28 个基础字段**（`SystemController#status`；配额开启时另加 2 个）：`llmConfigured`、`llmVerified`、`llmModel`、`llmHint`、`llmProvider`、`llmModelAvailable`、`llmModelError`、`llmModelCheckPending`、`amapConfigured`、`geoProvider`、`geoHint`、`timezone`、`routeProvider`、`routeConfigured`、`amapVerified`、`routeLastError`、`routeHint`、`mcpEnabled`、`mcpConnected`、`mcpToolCount`、`mcpServers`、`mcpLastError`、`mcpHint`、`amapMcpToolCount`、`priceProvider`、`priceHint`、`pendingPlanStore`、`rateLimitEnabled`，以及配额开启时的 `llmQuotaUsed`/`llmQuotaLimit`。路线只配 Key 但从未成功调用时，`routeProvider` 必须是 `estimated`（ADR-006），估算不伪装成实时。

> **启动期配置体检**：`SecurityStartupGuard` 在 `mysql` / `prod` profile 下会对「H2 控制台开启、demo 账号开启、`cookie-secure=false`」三项输出 `[SECURITY]` 前缀 WARN。它**只告警、不阻止启动**（避免把部署直接卡死），所以上线核对时要把这三条 WARN 当作**硬失败**处理：看到任何一条都先修配置再发布。

```bash
# 登录后带 Cookie 自检
curl -sS -c cookies.txt -X POST http://localhost:8081/api/auth/login \
  -H 'Content-Type: application/json' -d '{"username":"<账号>","password":"<密码>"}'
curl -sS -b cookies.txt http://localhost:8081/api/system/status
```

### 4.1 状态字段怎么判读（P0-A 已实现）

| 字段 | 取值 | 判读规则 |
| --- | --- | --- |
| `llmConfigured` | boolean | `LLM_ENABLED=true` 且 `LLM_API_KEY` 非空；只说明“配置有 Key”，不代表能调用 |
| `llmVerified` | boolean | 最近一次真实调用是否成功；从未调用或最近失败均为 false |
| `llmProvider` | string/null | `LLM_BASE_URL` 的 host（不含协议/路径/Key） |
| `llmModelAvailable` | true / false / **null** | 启动探测 `/models` 的结论；`null` = 未校验（未配 Key、探测关闭、探测请求失败），**不能当可用** |
| `llmModelError` | string/null | 不可用或未校验的原因（含可用模型清单摘要）；不含 Key |
| `llmModelCheckPending` | boolean | 探测仍在进行；此时 `llmModelAvailable=null` 属正常 |
| `mcpEnabled` / `mcpConnected` | boolean | `mcpConnected` 仅在“桥接已启动且至少一个 server 连接成功”时为 true |
| `mcpToolCount` | int | MCP 桥接注册的工具总数；0 = 没有可用的外部工具 |
| `amapMcpToolCount` | int | 从注册表发现的高德语义工具数；与 `mcpToolCount` 口径不同，分开看 |
| `mcpServers` | array | 每个 server 的 `name/connected/toolCount/lastError`，用于定位是哪一个失败 |
| `mcpLastError` / `mcpHint` | string | 最近一次接入失败原因与中文提示；`mcpHint` 不会夸大接入状态 |
| `routeProvider` | `amap` / `estimated` | 只有最近一次真实路径调用成功才允许 `amap` |
| `amapVerified` / `routeLastError` | boolean / string | 高德路径是否真实成功、失败原因（含 infocode，不含 Key） |
| `priceProvider` | string | 恒为 `reference-estimate`：价格只有本地参考估算，没有实时房态/库存 |
| `timezone` | string | 实际生效时区；排查“日期差一天”先看它 |
| `pendingPlanStore` | string | 恒为 `database`（待确认方案落库，多实例可用） |

> **仍缺的一项**：路线图 P0-A 还要求展示“最近验证时间”，当前接口只有布尔值与错误原因，**没有 `lastVerifiedAt` 之类的字段**；这属于 P0-A 的剩余差距，定位故障时暂用日志时间线 + `llm.calls`/`external.degraded` 指标。

---

## 5. 容器部署的变量透传核对（当前状态）

`docker-compose.yml` 已重写，backend 的环境变量基本全部显式透传。按当前文件逐项核对：

| 统计 | 数量 | 说明 |
| --- | --- | --- |
| 应用侧声明变量（两份 yml 的 `${VAR:default}` 去重） | **55** | 含 P0-A 新增的 `LLM_MODEL_PROBE_ENABLED` |
| 其中 compose **直接透传** | **50** | 其余 5 个见下一行 |
| 其中 compose **未透传** | **5** | `TELEGRAM_POLL_TIMEOUT` + `MYSQL_HOST` / `MYSQL_PORT` / `MYSQL_DATABASE` / `MYSQL_USERNAME`（后 4 个由 compose 以字面量固定） |
| 前端构建期变量 | **3** | `VITE_AMAP_KEY` / `VITE_AMAP_SECURITY_CODE` / `VITE_FOOTPRINT_BASEMAP`（build args） |
| 仅 Docker 面变量 | **4** | `MYSQL_ROOT_PASSWORD`、`SPRING_PROFILES_ACTIVE`、`JAVA_OPTS`、`TZ` |
| S3 映射输入 | **5** | `.env` 的 `S3_ENDPOINT/REGION/BUCKET/ACCESS_KEY/SECRET_KEY` → 容器的 `TRAVEL_PHOTOS_S3_*` |
| **用户可见变量合计** | **67** | 55 + 3 + 4 + 5；口径与 [部署与运维 §5.8](../guide/deployment.md) 一致 |
| compose 内部固定（不需要 `.env`） | 2 | `SPRING_DATA_REDIS_HOST=redis`、`SPRING_DATA_REDIS_PORT=6379`（外部 Redis 用 override 覆盖） |

> 本机**没有 Docker**，`docker compose config` **未执行**；本次只对 `docker-compose.yml` 做了 YAML 语法解析（`python -c "import yaml; yaml.safe_load(...)"`，见 §7）。真实渲染/启动验证属于未验证项。

### 5.1 仍然不能只靠 `.env` 改变的量

| 项 | 现状 | 影响 / 做法 |
| --- | --- | --- |
| `TELEGRAM_POLL_TIMEOUT` | compose 未透传 | 默认 25 秒，通常无需改；要改需 override |
| `MYSQL_HOST` / `MYSQL_PORT` / `MYSQL_DATABASE` / `MYSQL_USERNAME` | compose 字面量固定（`mysql` / `3306` / `travel_agent` / `travel`） | 换库/主机需 override；这 4 项写 `.env` 无效 |
| 外部 Redis 连接项 | compose 只固定内置 `redis` 的 host/port | 外部 Redis 用 `SPRING_DATA_REDIS_HOST/PORT` override；密码/TLS/database 用 override 或 `SPRING_APPLICATION_JSON` |
| S3 重试/超时（`max-retries`、`api-call-timeout-ms`） | 未暴露 | 用 `SPRING_APPLICATION_JSON` 注入 `travel.photos.s3.*` |
| 其他 Spring 配置 | 无独立环境变量 | override 或 `SPRING_APPLICATION_JSON` |

### 5.2 需要自定义时的 override 示例（不提交）

```yaml
# docker-compose.override.yml（本地/生产服务器上维护，不进仓库）
services:
  backend:
    environment:
      TELEGRAM_POLL_TIMEOUT: ${TELEGRAM_POLL_TIMEOUT:-25}
      # 换成外部 Redis（不需要密码时只写 host/port；密码/TLS 见下）
      SPRING_DATA_REDIS_HOST: ${REDIS_HOST:-redis}
      SPRING_DATA_REDIS_PORT: ${REDIS_PORT:-6379}
      # 非标准 S3 选项 / Redis 超时等，只能走 SPRING_APPLICATION_JSON：
      SPRING_APPLICATION_JSON: >-
        {"travel":{"photos":{"s3":{"max-retries":0,"api-call-timeout-ms":3000}}},
         "spring":{"data":{"redis":{"timeout":"2s"}}}}
```

> 常规部署不再需要这份 override：`RATE_LIMIT_*`、`LLM_*`、`EMBEDDING_*`、`PHOTOS_STORE`、`S3_*`、`TZ`/`APP_TIMEZONE`、`AGENT_BUDGET_*`、`CONVERSATION_*`、`WECHAT_OA_*`、`JAVA_OPTS` 都已由 compose 直接透传。

---

## 6. 启动与发布自检清单（生产打勾）

- [ ] `.env` 中 `JWT_SECRET` 是固定值、Base64 解码 48 字节；本次发布**没有更换**它（否则全体掉线）。
- [ ] `COOKIE_SECURE=true` 且站点确实走 HTTPS；反代透传 `X-Forwarded-Proto`。
- [ ] `DEMO_ACCOUNT=false`；若曾以 `true` 启动，已删除 `demo` 账号（`DELETE FROM users WHERE username='demo';`）。
- [ ] `H2_CONSOLE_ENABLED=false` 且容器内该变量确实未打开（`docker compose config` 核对）。
- [ ] `CORS_ALLOWED_ORIGINS` 是真实域名，无 `*`。
- [ ] `RATE_LIMIT_ENABLED=true`；反代部署已配 `RATE_LIMIT_TRUSTED_PROXIES`，且未把不受信网段纳入。
- [ ] `RATE_LIMIT_STORE` 与部署形态一致：compose 默认 `redis`，并确认 `redis` 容器 healthy；单实例若改 `memory`，已知限流失效时需人工告警。
- [ ] `LLM_MODEL` 已按当前 Key 的 `/models` 核对（当前：`deepseek-flash`）；`LLM_DAILY_QUOTA`、`LLM_MODEL_PROBE_ENABLED` 已实际透传（§5）。
- [ ] `/api/system/status` 的 `llmModelAvailable=true`（或明确记录“探测未完成/被关闭”的 `null` 原因）；`llmProvider` 是预期 host。
- [ ] 启用 MCP 时 `mcpEnabled=true`、`mcpConnected=true`、`mcpToolCount > 0`；为 0 时 `mcpLastError` 有可读原因。
- [ ] `AMAP_KEY` 与 `AMAP_MAPS_API_KEY` 都是「Web 服务」类型且不是同一个 Key；`VITE_AMAP_KEY` 是「Web 端(JS API)」。
- [ ] 用 `S3` 存照片时：`S3_BUCKET` / `S3_ACCESS_KEY` / `S3_SECRET_KEY` 已填，且 `docker compose config` 渲染出 `TRAVEL_PHOTOS_S3_*`。
- [ ] `APP_TIMEZONE=Asia/Shanghai`、`TZ=Asia/Shanghai`；`/api/system/status` 的 `timezone` 与之一致。
- [ ] MySQL/Redis/S3 口令是强随机值；轮换顺序为先改库内/控制台、后改环境变量。
- [ ] 日志轮转已配置（`backend`/`web` 的 `json-file` 为 10m × 3，待真实容器验证）；`docker compose logs web` 不再出现 `ticket=`（nginx `log_format without_args`，待真实容器验证）。
- [ ] 用 `docker compose config` 渲染过一遍最终配置，确认 §5.1 的 5 个未透传项符合预期。
- [ ] 启动日志无 `未配置环境变量 JWT_SECRET`、`已创建演示账号`、`[SECURITY]` 警告；`/api/system/status` 与 §4 的预期一致。

---

## 7. 未验证边界（不得写成已通过）

| 项 | 状态 | 说明 |
| --- | --- | --- |
| 用全新生产 `.env` 在真实环境启动 | **未验证** | 本文行为来自源码与既有文档；没有新的生产启动记录 |
| `docker compose config` 渲染 / 整栈启动 | **未执行** | 本机无 Docker；只做了 `docker-compose.yml` 的 YAML 语法解析（`python -c "import yaml; yaml.safe_load(...)"`） |
| nginx `log_format without_args` | **待真实容器验证** | 配置已写入 `travel-agent-web/nginx.conf`；未执行 `nginx -t` / 未重启容器验证 |
| Redis 服务与 `RATE_LIMIT_STORE=redis` | **未验证** | compose 已加 `redis` 服务与 healthcheck；本机无法启动验证多实例共享 |
| S3 `S3_*` → `TRAVEL_PHOTOS_S3_*` 映射 | **未验证** | 仅按 compose 文件核对变量名；未连真实对象存储 |
| 模型可用清单 | 2026-11-22 核对过一次 | 供应商会变；换 Key/provider 必须重核 |
| 启动模型探测（`llmModelAvailable`） | 有单测覆盖（`LlmModelProbeTest` / `SystemControllerStatusTest`），**未在本文用真实 Key 做一次启动验证** | 探测失败/关闭时为 `null`，不要当成可用 |
| 高德 Key 权限/配额 | **未在本文核对** | 需以控制台实际配置与合同为准；代码只在 `10021` 重试 1 次 |
| S3 真实读写与权限 | **未在本文验证** | 仅源码/单测层面的错误语义（见 [外部依赖降级矩阵](../guide/external-dependencies.md)） |
| Redis 多实限流 | **未在本文验证** | fail-open 行为有单测覆盖；生产多实例效果需演练 |
| 变量透传统计 | 已按 `docker-compose.yml` 逐项核对 | 改 compose 后必须重算 §5 |

相关文档：[部署与运维](../guide/deployment.md)（变量权威表与部署步骤）· [备份/恢复/迁移演练](backup-restore.md) · [故障处理手册](runbook.md) · [隐私政策](../legal/privacy-policy.md) · [第三方服务说明](../legal/third-party-services.md)