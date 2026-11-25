# 06 · 运行与部署

## 6.1 环境要求

| 工具 | 版本 |
| --- | --- |
| JDK | 17+ |
| Maven | 3.8+ |
| Node / npm | Node 20（CI 基线；Vite 6 支持 Node 18+） |
| 数据库 | 无需安装（默认 H2 文件库）；可选 MySQL 8.4 / Redis 7 |

## 6.2 本地开发

### 后端（:8080）

```bash
cd travel-agent-server
export JWT_SECRET="$(openssl rand -base64 48)"   # 可选；不设则每次启动随机（重启需重新登录）
export LLM_API_KEY="sk-你的key"                  # 可选；不设走规则规划器
mvn spring-boot:run
```

Windows 推荐 [run-dev.ps1](../../travel-agent-server/run-dev.ps1)：读取 `travel-agent-server/.env.local`（已 gitignore）并固定 `JWT_SECRET`。

首次启动自动完成：Flyway 迁移（V1→V24）→ 创建演示账号 **demo / demo123** → 加载城市目录（`resources/data/travel-catalog.json`）与旅游知识库（`resources/rag/knowledge.json`）。

### 前端（:5173）

```bash
cd travel-agent-web
npm install
# 可选：cp .env.example .env.local，填 VITE_AMAP_KEY / VITE_AMAP_SECURITY_CODE（Web 端 JS API 凭据）
npm run dev
```

打开 <http://localhost:5173>，用 demo / demo123 登录。dev server 将 `/api` 代理到 :8080。

### 本地切 MySQL

```bash
docker compose -f docker-compose.infra.yml up -d   # MySQL + Redis（自动执行 sql/init.sql 兼容初始化）
cd travel-agent-server
mvn spring-boot:run -Dspring-boot.run.profiles=mysql
```

## 6.3 测试

```bash
# 后端集成测试（内存 H2，不触网、不调真实模型）
cd travel-agent-server && mvn test

# 前端全部门禁：类型检查 + 单元测试 + 生产构建
cd travel-agent-web && npm run typecheck && npm test && npm run build

# 端到端（隔离后端：独立 H2，LLM/MCP/AMAP 全关；首次自动构建后端 jar）
cd travel-agent-web && npm run build && npm run e2e:isolated
```

其他常用：`npm run lint`、`npm run format:check`、`npm run build:check`（bundle 预算）、`npm run api:check`（OpenAPI 契约）、`npm run test:e2e:mobile` / `npm run test:e2e:network` / `npm run test:e2e:multi` / `npm run test:e2e:device`（e2e 套件）、`npm run test:visual`（视觉回归 + axe）。

CI（[.github/workflows/ci.yml](../../.github/workflows/ci.yml)）：push/PR 执行 `mvn -B verify` + 前端全部门禁 + 隔离后端 Playwright e2e。

## 6.4 Docker 全栈部署

```bash
JWT_SECRET=$(openssl rand -base64 48) docker compose up -d --build
# 访问 http://localhost:8081 （web 容器 nginx 反代 /api 到 backend）
```

[docker-compose.yml](../../docker-compose.yml) 四个服务：

| 服务 | 镜像/构建 | 说明 |
| --- | --- | --- |
| mysql | mysql:8.4 | utf8mb4；健康检查后 backend 才启动 |
| redis | redis:7-alpine | 多实例限流计数（`RATE_LIMIT_STORE=redis`）；故障 fail-open |
| backend | 构建 `travel-agent-server` | `SPRING_PROFILES_ACTIVE=mysql`；数据卷 `backend-data:/app/data`；默认只经 web 反代暴露 |
| web | 构建 `travel-agent-web` | nginx :8081→80；构建期注入 `VITE_AMAP_KEY` 等 |

网络细节：compose 固定子网 `172.31.240.0/24`，web 固定 IP `172.31.240.10`，供后端 `RATE_LIMIT_TRUSTED_PROXIES=/32` 精确信任 nginx（其他来源按各自源 IP 分桶）。

## 6.5 关键配置速查

全部配置定义在 [application.yml](../../travel-agent-server/src/main/resources/application.yml)（含降级语义注释），均可被环境变量覆盖。生产模板：[.env.production.example](../../.env.production.example)。

| 变量 | 默认 | 说明 |
| --- | --- | --- |
| `JWT_SECRET` | 空（启动生成一次性） | Base64，解码后 ≥32 字节；**生产必配** |
| `JWT_EXPIRE_HOURS` | 72 | token 有效期 |
| `COOKIE_SECURE` | false | https 部署设 true |
| `DEMO_ACCOUNT` | true | **生产必须 false** |
| `LLM_API_KEY` / `LLM_BASE_URL` / `LLM_MODEL` | 空 / deepseek / deepseek-flash | 空则全走规则规划器 |
| `LLM_DAILY_QUOTA` | 0（不限） | 每用户每日 LLM 上限，超限自动降级 |
| `EMBEDDING_ENABLED` | false | 真实语义检索；切模型须清库重建 |
| `AMAP_KEY` | 空 | 高德 Web 服务；空则本地估算（标注 estimated） |
| `WEATHER_SIMULATION` | false | 仅测试/离线演示可 true；生产保持 false |
| `MCP_ENABLED` | false | MCP 工具生态（预置高德 MCP stdio） |
| `RATE_LIMIT_ENABLED` / `RATE_LIMIT_STORE` | true / memory | 限流；多实例改 redis |
| `RATE_LIMIT_TRUSTED_PROXIES` | 空 | 默认不信任何代理、不读 XFF |
| `AGENT_BUDGET_ENABLED` / `AGENT_BUDGET_TOTAL_MS` | true / 30000 | Agent 端到端时间预算，耗尽降级并标注 |
| `PHOTOS_STORE` | local | local / s3 |
| `CONVERSATION_HISTORY_LIMIT` / `CONVERSATION_RETENTION_DAYS` | 10 / 7 | 会话记忆条数与保留期 |
| `APP_TIMEZONE` | Asia/Shanghai | 应用时区（容器 TZ 需同步） |
| `MYSQL_*` | — | 仅 mysql profile 生效 |
| `VITE_AMAP_KEY` / `VITE_AMAP_SECURITY_CODE` | — | 前端高德 JS API（写入 `travel-agent-web/.env.local`） |

## 6.6 生产上线要点

1. **必改**：`JWT_SECRET`、`DEMO_ACCOUNT=false`、`COOKIE_SECURE=true`（https）、`CORS_ALLOWED_ORIGINS` 改为真实域名。
2. 保持 `H2_CONSOLE_ENABLED=false`（sa 空密码直连，暴露即匿名读写全库）。
3. `RATE_LIMIT_TRUSTED_PROXIES` 填反代的确切 IP（不要填整个网段）。
4. 观测：`/actuator/prometheus`（需登录）+ Grafana 面板（[docs/operations/](../../docs/operations/README.md)）+ 告警规则（`docs/operations/alerts.yml`）。
5. 排障入口：`/api/system/status` 自报 LLM 模型可用性、天气 simulated、能力边界；排障手册见 `docs/operations/runbook.md`。
6. 备份恢复与 MySQL 迁移验证流程已实测：见 `docs/operations/backup-restore.md` 与 `docs/guide/mysql-migration.md`。

## 6.7 常见排障

| 现象 | 排查 |
| --- | --- |
| 重启后全员掉线 | 未固定 `JWT_SECRET`（每次启动随机） |
| 回复像模板、无 AI 感 | 未配 `LLM_API_KEY`，走规则规划器 —— 预期行为，非故障 |
| POI/路线标注 estimated | 未配 `AMAP_KEY` 或高德限流失败 —— 已自动回落 |
| 天气相关修改被拒（503） | 真实预报拿不到；系统故意不模拟。查网络或 `WEATHER_*` 配置 |
| 日志中文乱码（Windows） | 已统一 UTF-8（application.yml + .mvn/jvm.config）；检查终端编码 |
| 启动校验失败（缺列） | schema 被手工改过；`ddl-auto=validate` 是特性 —— 补 Flyway 迁移而不是改实体 |
