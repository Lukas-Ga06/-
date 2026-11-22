import { describe, expect, it } from 'vitest';
import { gcj02ToWgs84, toAmapLngLat, wgs84ToGcj02 } from './coords';

describe('coords', () => {
  it('把 WGS-84 转成 GCJ-02（上海人民广场参考值）', () => {
    const [lat, lng] = wgs84ToGcj02(31.2304, 121.4737);
    expect(lat).toBeCloseTo(31.2284577376, 6);
    expect(lng).toBeCloseTo(121.4782230593, 6);
  });

  it('GCJ-02 反算回 WGS-84 后与原点误差小于 1e-6 度', () => {
    const [gcjLat, gcjLng] = wgs84ToGcj02(31.2397, 121.4906);
    const [wgsLat, wgsLng] = gcj02ToWgs84(gcjLat, gcjLng);
    expect(wgsLat).toBeCloseTo(31.2397, 6);
    expect(wgsLng).toBeCloseTo(121.4906, 6);
  });

  it('境外坐标不做偏移', () => {
    expect(wgs84ToGcj02(48.8566, 2.3522)).toEqual([48.8566, 2.3522]);
    expect(gcj02ToWgs84(35.6762, 139.6503)).toEqual([35.6762, 139.6503]);
  });

  it('toAmapLngLat 返回高德要求的 [lng, lat] 顺序', () => {
    const [lng, lat] = toAmapLngLat(31.2304, 121.4737);
    expect(lng).toBeCloseTo(121.4782230593, 6);
    expect(lat).toBeCloseTo(31.2284577376, 6);
  });

  it('非法输入原样返回，不产生 NaN', () => {
    expect(wgs84ToGcj02(Number.NaN, 121.47)).toEqual([Number.NaN, 121.47]);
  });
});
