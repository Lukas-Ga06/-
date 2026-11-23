# 高德 REST 真实 Key 验收（2026-11-23）

> 目的：把「后端高德 Key 是 JS Key、会被拒」这一旧结论更新为可复核事实，并实测 1.5 里的高德边界项。
> 结论：当前 `travel-agent-server/.env.local` 的 `AMAP_KEY` **可作 Web 服务 Key 使用**：地理编码、POI、驾车路线均 `status=1/10000`；并发 20 请求复现 **9 次 `10021`（QPS 超限）**；跨城同名 POI 与 polyline 正常。
> 仍未验证：MCP 场景的 `AMAP_MAPS_API_KEY`（那是另一个 Key/变量，历史记录里仍是 `USERKEY_PLAT_NOMATCH`）、真实 LLM 20 次压测（余额 ¥0.66，按计划应先充值）。

## 1. 环境与方法

- Key 来源：`travel-agent-server/.env.local` 的 `AMAP_KEY`（32 位）；**Key 不写入仓库，本文只记录结果**。
- 直连高德 REST `restapi.amap.com`，不经过后端；方法为 `urllib` 串行/线程池 20 并发。
- 复现脚本（不含 Key）：见 §4。

## 2. 结果

| 用例 | 请求 | 结果 |
| --- | --- | --- |
| 地理编码 | `/v3/geocode/geo?city=上海&address=外滩` | `status=1`、`info=OK`、`infocode=10000` |
| 驾车路线 + polyline | `/v3/direction/driving` 上海人民广场 → 外滩 | `status=1`；距离 10,530 m、12 段 step，首段 `polyline` 长度 82（有坐标串） |
| 跨城同名 POI | `/v3/place/text?keywords=万达广场&city=110000` / `310000` | 北京返回通州区/丰台区，上海返回松江区/金山区等，`adname` 与 `location` 各自正确，未跨城串数据 |
| 并发 20 请求 | 20 个线程同时 `/v3/geocode/geo` | **11 × `10000` + 9 × `10021`**（CUQPS_HAS_EXCEEDED_THE_LIMIT），说明免费配额下 QPS 限制真实存在，可稳定复现 |

坐标口径：高德 REST 返回的是 **GCJ-02**（厂商契约）；本次未做 WGS-84 对照转换验证，应用侧统一按 GCJ-02 处理（`CoordinateTransform` 已有单测）。

## 3. 对应用的含义

- `AmapDataService` 的 MCP `10021` 退避（220–400ms 重试一次后回落 REST）有了真实触发条件：并发场景会稳定出现 `10021`，不再只是单测构造。
- 后端 REST 路径可用，此前「JS Key 会被 `USERKEY_PLAT_NOMATCH` 拒绝」只适用于 MCP 的 `AMAP_MAPS_API_KEY`，不适用于 `AMAP_KEY`。
- 生产仍应给高德单独申请**生产 Web 服务 Key**并配置 QPS/配额告警；本 Key 属个人配额，不能作为生产容量依据。

## 4. 复现（Key 从环境变量读，不落盘）

```bash
export AMAP_KEY='<你的 Web 服务 Key>'
# 地理编码
curl -s "https://restapi.amap.com/v3/geocode/geo?city=上海&address=外滩&key=$AMAP_KEY"
# 驾车路线（看 route.paths[0].steps[*].polyline）
curl -s "https://restapi.amap.com/v3/direction/driving?origin=121.4737,31.2304&destination=121.4906,31.2397&key=$AMAP_KEY"
# 20 并发观察 10021（任一脚本均可；本次用 Python ThreadPoolExecutor）
```

## 5. 证据

- 本次输出来自本机实时调用，未入库（Key 敏感）；关键结果已抄录在上表。
- 相关的应用侧降级与状态自报：`/api/system/status` 的 `amapConfigured/geoProvider/routeProvider`、`external_degraded_total{provider="amap"}`（告警 `TravelExternalProviderDegraded`，15 分钟窗口，本次未实跑）。