/**
 * 坐标系约定（全仓唯一口径）：
 *
 * - **存储 / API / 业务计算一律 WGS-84**：与 OSM、GPS、MapLibre 底图一致。
 * - **高德底图使用 GCJ-02**：只在 `AMapProvider` 渲染前转换，不污染业务数据。
 * - 足迹地图（MapLibre + OSM）直接用 WGS-84，不做任何偏移。
 *
 * 高德官方也说明：GPS 坐标需经 `AMap.convertFrom()` 或等价算法偏移后才能与高德底图匹配
 * （https://lbs.amap.com/api/javascript-api-v2/guide/abc/basetype）。这里用离线公式，避免额外网络请求。
 */

const PI = Math.PI;
/** 克拉索夫斯基椭球长半轴 */
const AXIS = 6378245.0;
/** 椭球偏心率平方。GCJ-02 公开算法里的固定常数，精度损失是算法定义的一部分，不是笔误 */
// eslint-disable-next-line no-loss-of-precision -- 常数按算法原值书写，截断到 double 精度不影响换算结果
const EE = 0.00669342162296594323;

/** 国境粗判：境外不做偏移（高德对境外坐标同样不纠正） */
function outOfChina(lat: number, lng: number): boolean {
  return lng < 72.004 || lng > 137.8347 || lat < 0.8293 || lat > 55.8271;
}

function transformLat(x: number, y: number): number {
  let ret = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * Math.sqrt(Math.abs(x));
  ret += ((20.0 * Math.sin(6.0 * x * PI) + 20.0 * Math.sin(2.0 * x * PI)) * 2.0) / 3.0;
  ret += ((20.0 * Math.sin(y * PI) + 40.0 * Math.sin((y / 3.0) * PI)) * 2.0) / 3.0;
  ret += ((160.0 * Math.sin((y / 12.0) * PI) + 320 * Math.sin((y * PI) / 30.0)) * 2.0) / 3.0;
  return ret;
}

function transformLng(x: number, y: number): number {
  let ret = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * Math.sqrt(Math.abs(x));
  ret += ((20.0 * Math.sin(6.0 * x * PI) + 20.0 * Math.sin(2.0 * x * PI)) * 2.0) / 3.0;
  ret += ((20.0 * Math.sin(x * PI) + 40.0 * Math.sin((x / 3.0) * PI)) * 2.0) / 3.0;
  ret += ((150.0 * Math.sin((x / 12.0) * PI) + 300.0 * Math.sin((x / 30.0) * PI)) * 2.0) / 3.0;
  return ret;
}

/** WGS-84 → GCJ-02，返回 `[lat, lng]`；境外原样返回 */
export function wgs84ToGcj02(lat: number, lng: number): [number, number] {
  if (!Number.isFinite(lat) || !Number.isFinite(lng) || outOfChina(lat, lng)) {
    return [lat, lng];
  }
  let dLat = transformLat(lng - 105.0, lat - 35.0);
  let dLng = transformLng(lng - 105.0, lat - 35.0);
  const radLat = (lat / 180.0) * PI;
  let magic = Math.sin(radLat);
  magic = 1 - EE * magic * magic;
  const sqrtMagic = Math.sqrt(magic);
  dLat = (dLat * 180.0) / (((AXIS * (1 - EE)) / (magic * sqrtMagic)) * PI);
  dLng = (dLng * 180.0) / ((AXIS / sqrtMagic) * Math.cos(radLat) * PI);
  return [lat + dLat, lng + dLng];
}

/**
 * GCJ-02 → WGS-84，返回 `[lat, lng]`；境外原样返回。
 * 用三次不动点迭代收敛，境内误差 < 1e-7°，远小于一个像素。
 */
export function gcj02ToWgs84(lat: number, lng: number): [number, number] {
  if (!Number.isFinite(lat) || !Number.isFinite(lng) || outOfChina(lat, lng)) {
    return [lat, lng];
  }
  let wLat = lat;
  let wLng = lng;
  for (let i = 0; i < 3; i += 1) {
    const [gLat, gLng] = wgs84ToGcj02(wLat, wLng);
    wLat += lat - gLat;
    wLng += lng - gLng;
  }
  return [wLat, wLng];
}

/** 高德相机 / 覆盖物需要 `[lng, lat]`；入参是 WGS-84，返回 GCJ-02 顺序值 */
export function toAmapLngLat(lat: number, lng: number): [number, number] {
  const [gcjLat, gcjLng] = wgs84ToGcj02(lat, lng);
  return [gcjLng, gcjLat];
}
