import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  AMAP_ATTRIBUTION,
  buildAmapRasterStyle,
  buildOsmRasterStyle,
  defaultFootprintBasemap,
} from './basemap';

function sourceOf(style: ReturnType<typeof buildAmapRasterStyle>) {
  return style.sources.base as {
    type: string;
    tiles: string[];
    tileSize: number;
    attribution: string;
  };
}

function paintOf(style: ReturnType<typeof buildAmapRasterStyle>) {
  const layer = style.layers.find((item) => item.id === 'base');
  return (layer as unknown as { paint: Record<string, number> }).paint;
}

describe('footprint basemap', () => {
  afterEach(() => {
    vi.unstubAllEnvs();
  });

  it('默认使用高德栅格底图，并保留球体可用的 raster source', () => {
    const style = buildAmapRasterStyle('light');
    const source = sourceOf(style);
    expect(source.type).toBe('raster');
    expect(source.tileSize).toBe(256);
    expect(source.tiles).toHaveLength(4);
    expect(source.tiles[0]).toContain('webrd01.is.autonavi.com');
    expect(source.tiles[0]).toContain('scale=1');
    expect(source.tiles[0]).not.toContain('scl=1');
    expect(source.attribution).toBe(AMAP_ATTRIBUTION);
    expect(source.tiles[0]).toContain('{x}');
    expect(source.tiles[0]).toContain('{y}');
    expect(source.tiles[0]).toContain('{z}');
  });

  it('高德底图保留纸质与暗色两套压色参数', () => {
    expect(paintOf(buildAmapRasterStyle('light'))['raster-saturation']).toBeLessThan(0);
    expect(paintOf(buildAmapRasterStyle('dark'))['raster-brightness-max']).toBeLessThan(1);
  });

  it('OSM 兜底样式仍可用', () => {
    const style = buildOsmRasterStyle('dark');
    expect(sourceOf(style).tiles[0]).toContain('tile.openstreetmap.org');
    expect(sourceOf(style).attribution).toContain('OpenStreetMap');
  });

  it('环境变量可一键退回 OSM', () => {
    vi.stubEnv('VITE_FOOTPRINT_BASEMAP', 'osm');
    expect(defaultFootprintBasemap()).toBe('osm');
  });
});