# 高德 POI / 地理编码质量验收（精编证据，2026-11-26）

> 原始证据（含逐条响应，本机、含时间戳目录）已 gitignore；本文件是可入库的精编摘要。
> 脚本：`scripts/amap-poi-quality.ps1` ｜ 完整评审：[2026-11-26-real-data-acceptance.md](../../../docs/review/2026-11-26-real-data-acceptance.md)

## 结论

**8/8 PASS**（真实高德 Web 服务 Key，REST；Key 指纹 `5f74d350****`，不落 Key/完整 URL）。
生成时间：2026-11-26 12:50:16。

| 编号 | 项目 | 结果 | 详情 |
| --- | --- | --- | --- |
| POI-1 | POI[西湖/杭州] | PASS | top1="杭州西湖风景名胜区" city="杭州市" 期望关键词排名=1 同城=True 坐标合规=True |
| POI-2 | POI[故宫/北京] | PASS | top1="故宫博物院" city="北京市" 期望关键词排名=1 同城=True 坐标合规=True |
| POI-3 | POI[外滩/上海] | PASS | top1="外滩" city="上海市" 期望关键词排名=1 同城=True 坐标合规=True |
| POI-4 | POI[宽窄巷子/成都] | PASS | top1="宽窄巷子景区" city="成都市" 期望关键词排名=1 同城=True 坐标合规=True（首次运行撞 10021，退避重试后通过） |
| POI-5 | POI[兵马俑/西安] | PASS | top1="秦始皇兵马俑博物馆" city="西安市" 期望关键词排名=1 同城=True 坐标合规=True |
| GEO-1 | GEO[西湖风景区/杭州] | PASS | formatted="浙江省杭州市西湖区" city="杭州市" 同城=True 坐标合规=True |
| GEO-2 | GEO[故宫博物院/北京] | PASS | formatted="北京市东城区故宫博物院" city="北京市" 同城=True 坐标合规=True |
| GEO-3 | GEO[外滩/上海] | PASS | formatted="上海市浦东新区外滩" city="上海市" 同城=True 坐标合规=True |

## 口径

- 期望关键词出现在 top5 内、POI 城市等于查询城市、坐标落在中国包围盒内（73–135°E / 3–54°N）；
- 只评价「命中/同城/坐标合规」，不评价排序；
- 10021（`CUQPS_HAS_EXCEEDED_THE_LIMIT`）与生产同策略退避重试一次，不把限流误判成数据质量。