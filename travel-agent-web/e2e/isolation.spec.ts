import { test } from '@playwright/test';
import { expect, expectOk } from './support';

/**
 * 发布门禁的隔离自证：e2e 后端必须是一个“没有任何真实外部依赖”的实例。
 *
 * 为什么这在浏览器套件里做，而不是只写在 CI 的注释里：
 * 2026-11-22 的 device 套件曾因为开发者 8080 后端加载了 `.env.local`（真实 LLM/高德 Key）
 * 出现 19/21：不是代码坏了，而是**测试对象不是隔离环境**。门禁如果能被环境污染，
 * 它就不能证明“干净环境可发布”。
 *
 * 只在 `E2E_ASSERT_ISOLATION=1` 时运行（由 `npm run e2e:isolated` 设置），
 * 普通开发者的 `npx playwright test`（连 8080 开发后端）不会因真实 Key 而误红。
 */
test('e2e 后端是隔离实例：LLM / MCP / 高德 / 真实 Key 全部关闭', async ({ request }) => {
  test.skip(
    process.env.E2E_ASSERT_ISOLATION !== '1',
    '仅隔离门禁运行；本地快速迭代用 `npm run e2e:isolated` 开启',
  );

  const response = await request.get('/api/system/status');
  await expectOk(response, '取运行状态 GET /api/system/status');
  const payload = await response.json();
  const status = payload.data;

  // LLM：配置与真实调用都必须为 false，模型探测不应误报可用
  expect(status.llmConfigured, 'LLM 配置态必须关闭').toBe(false);
  expect(status.llmVerified, 'LLM 真实调用态必须为 false').toBe(false);

  // 地图/路线：没有 AMAP_KEY，路线只能是本地估算，不能伪装成实时
  expect(status.amapConfigured, '高德 Web 服务 Key 必须为空').toBe(false);
  expect(status.geoProvider, '定位 provider 必须回退 osm').toBe('osm');
  expect(status.routeProvider, '路线 provider 必须回退 estimated').toBe('estimated');
  expect(status.amapVerified, '高德不得出现已验证状态').toBe(false);

  // MCP：隔离实例整体关闭；即使字段暂未提供，工具数也必须为 0（兼容旧后端）
  expect(status.mcpToolCount ?? 0, 'MCP 工具数必须为 0').toBe(0);
  expect(status.mcpEnabled ?? false, 'MCP 必须关闭').toBe(false);

  // 时区固定，避免日期断言的“昨天/明天”随 runner 漂移
  expect(status.timezone).toBe('Asia/Shanghai');

  // 价格只能是本地参考估算，不能冒充 OTA 实时库存
  expect(status.priceProvider).toBe('reference-estimate');
});