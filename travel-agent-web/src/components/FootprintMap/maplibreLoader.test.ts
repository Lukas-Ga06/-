import { afterEach, describe, expect, it } from 'vitest';
import {
  assertMaplibreModule,
  buildMaplibreUrl,
  loadMaplibre,
  resetMaplibreLoaderForTest,
  setMaplibreModuleImporterForTest,
} from './maplibreLoader';

/** 最小可用的「假 maplibre 模块」：只需导出校验通过，不真的渲染地图 */
const validExports = {
  Map: class {},
  Marker: class {},
  Popup: class {},
  LngLatBounds: class {},
  AttributionControl: class {},
};

afterEach(() => resetMaplibreLoaderForTest());

describe('buildMaplibreUrl', () => {
  it('根路径部署拼出带版本目录的 vendor URL', () => {
    expect(buildMaplibreUrl('/')).toBe('/vendor/maplibre/6.19.0/maplibre-gl.mjs');
    expect(buildMaplibreUrl('/', '6.19.0')).toBe('/vendor/maplibre/6.19.0/maplibre-gl.mjs');
  });

  it('子路径部署保留 BASE_URL，并容忍缺少结尾斜杠', () => {
    expect(buildMaplibreUrl('/travel/')).toBe('/travel/vendor/maplibre/6.19.0/maplibre-gl.mjs');
    expect(buildMaplibreUrl('/travel')).toBe('/travel/vendor/maplibre/6.19.0/maplibre-gl.mjs');
  });
});

describe('assertMaplibreModule', () => {
  it('导出齐全时原样返回', () => {
    expect(assertMaplibreModule(validExports)).toBe(validExports);
  });

  it('缺少必要导出时报出缺了哪些', () => {
    expect(() => assertMaplibreModule({ Map: class {} })).toThrow(
      /缺少必要的导出：Marker, Popup, LngLatBounds, AttributionControl/,
    );
    expect(() => assertMaplibreModule(null)).toThrow(/为空或不是 ESM 模块/);
  });
});

describe('loadMaplibre', () => {
  it('并发调用共享同一个 Promise，成功后缓存单例', async () => {
    let calls = 0;
    let release: (value: unknown) => void = () => {};
    const gate = new Promise<unknown>((resolve) => {
      release = resolve;
    });
    setMaplibreModuleImporterForTest(() => {
      calls += 1;
      return gate;
    });

    const first = loadMaplibre();
    const second = loadMaplibre();
    release(validExports);
    const [a, b] = await Promise.all([first, second]);

    expect(calls).toBe(1);
    expect(a).toBe(validExports);
    expect(b).toBe(validExports);
    expect(await loadMaplibre()).toBe(validExports);
    expect(calls).toBe(1);
  });

  it('用 BASE_URL 拼出的 vendor URL 去 import', async () => {
    const seen: string[] = [];
    setMaplibreModuleImporterForTest(async (url) => {
      seen.push(url);
      return validExports;
    });

    await loadMaplibre();

    expect(seen).toEqual([buildMaplibreUrl(import.meta.env.BASE_URL)]);
    expect(seen[0]).toBe('/vendor/maplibre/6.19.0/maplibre-gl.mjs');
  });

  it('加载失败时抛出带 URL 的清晰错误，并且可以重试成功', async () => {
    let calls = 0;
    setMaplibreModuleImporterForTest(async () => {
      calls += 1;
      if (calls === 1) {
        throw new Error('Failed to fetch dynamically imported module');
      }
      return validExports;
    });

    await expect(loadMaplibre()).rejects.toThrow(
      /地图引擎加载失败（\/vendor\/maplibre\/6\.19\.0\/maplibre-gl\.mjs）：Failed to fetch/,
    );

    // 失败后缓存已清空：第二次会重新 import 并成功
    await expect(loadMaplibre()).resolves.toBe(validExports);
    expect(calls).toBe(2);
  });

  it('导出校验失败同样可重试（坏模块不会被缓存）', async () => {
    let calls = 0;
    setMaplibreModuleImporterForTest(async () => {
      calls += 1;
      return calls === 1 ? { Map: class {} } : validExports;
    });

    await expect(loadMaplibre()).rejects.toThrow(/缺少必要的导出/);
    await expect(loadMaplibre()).resolves.toBe(validExports);
    expect(calls).toBe(2);
  });
});
