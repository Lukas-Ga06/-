import type { StyleSpecification } from 'maplibre-gl';

/**
 * 足迹地图底图。
 *
 * 默认使用高德栅格瓦片：足迹页需要 MapLibre 的球体投影，而高德 JS API 2.0
 * 没有球体视图，所以这里只把「底图」换成高德，渲染引擎仍是 MapLibre。
 * 高德瓦片使用 GCJ-02 坐标，`FootprintMap` 会把 WGS-84 业务坐标转成 GCJ-02 后再上图。
 *
 * 注意：`webrd0x.is.autonavi.com` 是高德的前端瓦片直连地址，不是面向第三方引擎的
 * 正式开放服务。对外商用前需要确认高德地图的授权/配额；可通过
 * `VITE_FOOTPRINT_BASEMAP=osm` 一键退回 OSM 底图。
 */
export type FootprintBasemap = 'amap' | 'osm';

/** 高德瓦片（矢量路网 + 中文注记）。四个子域轮询，MapLibre 会按 URL 数组分发。 */
export const AMAP_TILES = [1, 2, 3, 4].map(
  (n) =>
    `https://webrd0${n}.is.autonavi.com/appmaptile?lang=zh_cn&size=1&scale=1&style=7&x={x}&y={y}&z={z}`,
);

export const AMAP_ATTRIBUTION = '© 高德地图';
export const OSM_ATTRIBUTION = '© OpenStreetMap contributors';

/** 环境变量缺省值：未显式指定时走高德；设成 `osm` 可完全退回原底图。 */
export function defaultFootprintBasemap(): FootprintBasemap {
  return import.meta.env.VITE_FOOTPRINT_BASEMAP === 'osm' ? 'osm' : 'amap';
}

const PAPER = { light: '#f3ead8', dark: '#12151b' } as const;

const AMAP_PAINT = {
  light: {
    // 把高德的彩色路网压成一张纸质描线图：强降饱和、略降对比、提亮暗部。
    'raster-saturation': -0.55,
    'raster-brightness-min': 0.06,
    'raster-brightness-max': 1,
    'raster-contrast': -0.1,
  },
  dark: {
    // 深色主题不做反相，只压暗并降饱和，避免夜间出现刺眼的白色图面。
    'raster-saturation': -0.65,
    'raster-brightness-min': 0.02,
    'raster-brightness-max': 0.38,
    'raster-contrast': 0.2,
  },
} as const;

/** 高德栅格底图：球体投影下仍是同一套 raster source。 */
export function buildAmapRasterStyle(theme: 'light' | 'dark'): StyleSpecification {
  const dark = theme === 'dark';
  return {
    version: 8,
    sources: {
      base: {
        type: 'raster',
        tiles: AMAP_TILES,
        tileSize: 256,
        minzoom: 1,
        maxzoom: 18,
        attribution: AMAP_ATTRIBUTION,
      },
    },
    layers: [
      {
        // 瓦片未加载或留白处也是「纸」的颜色，而不是刺眼的纯白。
        id: 'paper',
        type: 'background',
        paint: { 'background-color': dark ? PAPER.dark : PAPER.light },
      },
      {
        id: 'base',
        type: 'raster',
        source: 'base',
        paint: dark ? AMAP_PAINT.dark : AMAP_PAINT.light,
      },
    ],
  };
}

/** OSM 栅格兜底（也是 `VITE_FOOTPRINT_BASEMAP=osm` 时的浅色样式）。 */
export function buildOsmRasterStyle(theme: 'light' | 'dark'): StyleSpecification {
  const dark = theme === 'dark';
  return {
    version: 8,
    sources: {
      base: {
        type: 'raster',
        tiles: ['https://tile.openstreetmap.org/{z}/{x}/{y}.png'],
        tileSize: 256,
        attribution: OSM_ATTRIBUTION,
      },
    },
    layers: [
      {
        id: 'paper',
        type: 'background',
        paint: { 'background-color': dark ? PAPER.dark : PAPER.light },
      },
      {
        id: 'base',
        type: 'raster',
        source: 'base',
        paint: dark
          ? {
              'raster-saturation': -0.4,
              'raster-brightness-min': 0.02,
              'raster-brightness-max': 0.42,
              'raster-contrast': 0.18,
            }
          : {
              'raster-saturation': -0.45,
              'raster-brightness-min': 0.08,
              'raster-brightness-max': 1,
              'raster-contrast': -0.12,
            },
      },
    ],
  };
}