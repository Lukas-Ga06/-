/**
 * MapLibre 运行时按需加载器（自托管、带版本目录）。
 *
 * 为什么不在 bundle 里 import：
 * - maplibre-gl 6.19 的 ESM 主文件约 1.1 MB、worker 约 550 KB，是单文件 bundle，
 *   Rollup 无法在内部再拆分；只要它进入 import 图，就会产出一个 >800 KB 的 chunk
 *   （Vite 会告警），而且 PWA 会把它塞进 precache，所有页面首次访问都为地图买单。
 * - 这里改为「页面真正需要渲染地图时」再 import 一个自托管的静态 URL：
 *   `${BASE_URL}vendor/maplibre/<version>/maplibre-gl.mjs`。
 *   该 URL 由 vite.config.ts 的 maplibreVendor 插件提供（dev 中间件 / build 拷贝到 dist）。
 *
 * 文件名必须是官方 `maplibre-gl.mjs`：maplibre 内部用 `import.meta.url` 推导同目录下的
 * `maplibre-gl-worker.mjs`，因此两个文件必须原样同目录部署，worker 的相对 URL 才可解析。
 *
 * 版本常量与 node_modules/maplibre-gl 必须一致：`maplibreLoader.test.ts` 和
 * `scripts/check-bundle-budget.mjs` 都会做漂移断言，升级依赖时不能只改 package.json。
 */
export const MAPLIBRE_VERSION = '6.19.0';

/** 地图引擎主模块相对站点根目录的 URL（BASE_URL 支持子路径部署） */
export function buildMaplibreUrl(baseUrl: string, version: string = MAPLIBRE_VERSION): string {
  const base = baseUrl.endsWith('/') ? baseUrl : `${baseUrl}/`;
  return `${base}vendor/maplibre/${version}/maplibre-gl.mjs`;
}

/** 只取类型：不会产生任何运行时 import，Rollup 也就不会把 maplibre 拉进 bundle */
export type MapLibre = typeof import('maplibre-gl');

/** 缺任何一个都无法渲染现有足迹地图：加载后立即校验，避免「模块加载成功但一用就崩」 */
const REQUIRED_EXPORTS = ['Map', 'Marker', 'Popup', 'LngLatBounds', 'AttributionControl'] as const;

type ModuleImporter = (url: string) => Promise<unknown>;

const defaultImporter: ModuleImporter = (url) => import(/* @vite-ignore */ url);

let importer: ModuleImporter = defaultImporter;
let pending: Promise<MapLibre> | null = null;

/**
 * 校验模块导出是否齐全。单独导出便于单测覆盖「坏模块」分支，
 * 也避免测试真的去加载 1.1 MB 的 vendor 文件。
 */
export function assertMaplibreModule(module: unknown): MapLibre {
  if (module == null || typeof module !== 'object') {
    throw new Error('地图引擎模块为空或不是 ESM 模块');
  }
  const missing = REQUIRED_EXPORTS.filter(
    (name) => typeof (module as Record<string, unknown>)[name] !== 'function',
  );
  if (missing.length > 0) {
    throw new Error(`地图引擎模块缺少必要的导出：${missing.join(', ')}`);
  }
  return module as MapLibre;
}

function toLoadError(error: unknown, url: string): Error {
  const detail = error instanceof Error ? error.message : String(error);
  const wrapped = new Error(`地图引擎加载失败（${url}）：${detail}`);
  // 保留原始错误便于 DevTools 里展开堆栈（target esnext，但不依赖 ES2022 的 ErrorOptions 类型）
  (wrapped as Error & { cause?: unknown }).cause = error;
  return wrapped;
}

/**
 * 加载地图引擎（单例）。并发调用共享同一个 Promise；失败时清空缓存，允许调用方重试。
 */
export function loadMaplibre(): Promise<MapLibre> {
  if (pending) {
    return pending;
  }
  const url = buildMaplibreUrl(import.meta.env.BASE_URL);

  // const 而非 let：`attempt` 只在初始化时赋值一次；catch 里的自引用发生在 Promise 结算之后，
  // 那时常量早已完成绑定，因此既满足 prefer-const，也能正确判断「失败的还是不是当前这次尝试」。
  const attempt: Promise<MapLibre> = Promise.resolve()
    .then(() => importer(url))
    .then((module) => assertMaplibreModule(module))
    .catch((error: unknown) => {
      // 只有「当前这次尝试」才允许清缓存：重试期间旧 Promise 的失败不能误伤新 Promise
      if (pending === attempt) {
        pending = null;
      }
      throw toLoadError(error, url);
    });
  pending = attempt;
  return attempt;
}

/**
 * 测试钩子：替换模块加载器（避免单测真的走网络/文件系统）并清空单例缓存。
 * 传 `null` 恢复真实动态 import。
 */
export function setMaplibreModuleImporterForTest(next: ModuleImporter | null): void {
  importer = next ?? defaultImporter;
  pending = null;
}

/** 测试钩子：清空单例缓存与注入的加载器 */
export function resetMaplibreLoaderForTest(): void {
  importer = defaultImporter;
  pending = null;
}
