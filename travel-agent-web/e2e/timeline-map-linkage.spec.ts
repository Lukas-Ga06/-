import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { test, type Page } from '@playwright/test';
import { expect, expectOk } from './support';
import { installFakeAMap, readFakeAMap } from './support-amap';

/**
 * P1-E 4/4：行程时间线 ↔ 地图双向联动。
 *
 * 两个方向各自一条场景，断言都落在用户能看到的东西上：
 * 1) 点时间线卡片 → 地图对应标记被选中（`data-selected`）并平移过去，
 *    且**不动缩放**（选中是平移，不是重设视角），两侧的来源标签都还在；
 * 2) 点地图标记 → 时间线回写同一个 `selectedElement`、滚动到 `#ti-<id>`；
 *    跨 Day 的条目会先切 Day（不切的话那一行根本不存在——不造行）。
 *
 * 为什么用替身 SDK：隔离后端关闭了 AMAP、构建也没有 Key，真实高德拉不起来，
 * 地图只剩失败态、没有可点的标记。替身在第三方边界上顶替（见 e2e/support-amap.ts），
 * 标记 DOM 与点击链路仍是应用真实的代码。
 */

const webRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
/** 证据目录用本机时钟命名（UTC 时间戳对 Asia/Shanghai 的操作者不直观） */
const STAMP = (() => {
  const pad = (value: number) => String(value).padStart(2, '0');
  const now = new Date();
  return `${now.getFullYear()}${pad(now.getMonth() + 1)}${pad(now.getDate())}-${pad(
    now.getHours(),
  )}${pad(now.getMinutes())}${pad(now.getSeconds())}`;
})();
const EVIDENCE_DIR = path.join(webRoot, '..', '.evidence', `ui-linkage-${STAMP}`);

/** 上海真实坐标（WGS-84）：地图标记有坐标才画得出来 */
const DAY1_SPOTS = [
  { title: '外滩', lat: 31.2397, lng: 121.4903 },
  { title: '豫园', lat: 31.2272, lng: 121.492 },
  { title: '南京路步行街', lat: 31.2386, lng: 121.4767 },
  { title: '人民广场', lat: 31.2304, lng: 121.4737 },
  { title: '上海博物馆', lat: 31.2286, lng: 121.4757 },
  { title: '田子坊', lat: 31.2089, lng: 121.4695 },
  { title: '新天地', lat: 31.2196, lng: 121.4744 },
  { title: '静安寺', lat: 31.2239, lng: 121.4453 },
  { title: '武康路', lat: 31.2077, lng: 121.4341 },
  { title: '徐家汇', lat: 31.1943, lng: 121.4365 },
];
const DAY2_SPOT = { title: '陆家嘴', lat: 31.2397, lng: 121.4997 };

interface Seeded {
  tripId: number;
  day1: { id: number; title: string; lat: number; lng: number }[];
  day2: { id: number; title: string; lat: number; lng: number };
}

function ok(data: unknown) {
  return { status: 200, contentType: 'application/json', body: JSON.stringify({ code: 0, data }) };
}

/**
 * 造一趟两天、11 个点的行程。
 * Day 1 有 10 条：时间线容器（62vh）装不下，最后一条一开始不在可视区，
 * 「点了标记之后真的滚过去」才有可观察的差异。
 */
async function seedTrip(page: Page): Promise<Seeded> {
  const created = await page.request.post('/api/trips', {
    data: {
      name: `e2e-linkage-${Date.now()}`,
      destination: '上海',
      startDate: '2026-12-01',
      endDate: '2026-12-02',
      budget: 5000,
    },
  });
  await expectOk(created, '创建行程 POST /api/trips');
  const tripId = (await created.json()).data.id as number;

  const addItem = async (data: Record<string, unknown>) => {
    const response = await page.request.post(`/api/trips/${tripId}/itinerary`, { data });
    await expectOk(response, '新增行程项 POST /api/trips/:id/itinerary');
    return (await response.json()).data.id as number;
  };

  const day1: Seeded['day1'] = [];
  for (const [index, spot] of DAY1_SPOTS.entries()) {
    const startHour = 8 + index;
    const id = await addItem({
      dayIndex: 1,
      itemType: index === 1 ? 'restaurant' : 'attraction',
      title: spot.title,
      startTime: `${String(startHour).padStart(2, '0')}:00`,
      endTime: `${String(startHour).padStart(2, '0')}:40`,
      latitude: spot.lat,
      longitude: spot.lng,
      description: `${spot.title}的行程说明：用于让时间线卡片有真实高度。`,
    });
    day1.push({ id, ...spot });
  }
  const day2Id = await addItem({
    dayIndex: 2,
    itemType: 'attraction',
    title: DAY2_SPOT.title,
    startTime: '10:00',
    endTime: '12:00',
    latitude: DAY2_SPOT.lat,
    longitude: DAY2_SPOT.lng,
  });

  return { tripId, day1, day2: { id: day2Id, ...DAY2_SPOT } };
}

/**
 * 转场段桩：一段 amap（实测）+ 一段 estimated（估算）。
 * 隔离后端没有高德，真实接口只会回估算；这里让两种来源同时出现在地图与时间线上，
 * 才能验证「联动之后两侧都还看得到来源」，而不是只验证其中一种。
 */
async function stubRouteLegs(page: Page, trip: Seeded): Promise<void> {
  const [first, second, third] = trip.day1;
  const legs = [
    {
      dayIndex: 1,
      sequence: 1,
      fromItemId: first.id,
      toItemId: second.id,
      fromTitle: first.title,
      toTitle: second.title,
      fromLatitude: first.lat,
      fromLongitude: first.lng,
      toLatitude: second.lat,
      toLongitude: second.lng,
      distanceKm: 3.4,
      walkMinutes: 45,
      driveMinutes: 12,
      taxiCost: 18,
      source: 'amap',
    },
    {
      dayIndex: 1,
      sequence: 2,
      fromItemId: second.id,
      toItemId: third.id,
      fromTitle: second.title,
      toTitle: third.title,
      fromLatitude: second.lat,
      fromLongitude: second.lng,
      toLatitude: third.lat,
      toLongitude: third.lng,
      distanceKm: 2.1,
      walkMinutes: 28,
      driveMinutes: 8,
      taxiCost: 14,
      source: 'estimated',
    },
  ];
  await page.route(`**/api/trips/${trip.tripId}/route-legs`, (route) => route.fulfill(ok(legs)));
}

async function openTrip(page: Page, trip: Seeded): Promise<void> {
  await page.goto(`/trip/${trip.tripId}`);
  // 地图真的画出来（替身 SDK 的 complete 之后应用会写 data-map-ready=ok）
  await expect(page.locator('[data-map-ready="ok"]')).toBeVisible({ timeout: 20_000 });
  await expect(page.locator('.m3d-marker').first()).toBeVisible({ timeout: 20_000 });
}

test('点时间线 → 地图选中对应标记并平移定位，缩放不变、两侧来源标签都在', async ({ page }) => {
  const trip = await seedTrip(page);
  await stubRouteLegs(page, trip);
  await installFakeAMap(page);
  await openTrip(page, trip);

  // 选第二条（豫园）：它的「进入段」是 amap，标记上应当能读到实测
  const target = trip.day1[1];
  const marker = page.locator(`.m3d-marker[data-item-id="${target.id}"]`);
  await expect(marker).toHaveAttribute('data-selected', 'false');

  const before = await readFakeAMap(page);

  await page.locator(`#ti-${target.id} .t-card .tt`).click();

  // ① 地图侧：同一条安排的标记被选中（地图没有自己的选中态，是 selectedElement 的投影）
  await expect(marker).toHaveAttribute('data-selected', 'true');
  await expect(page.locator(`#ti-${target.id}`)).toHaveClass(/selected/);

  // ② 相机只平移：缩放没有被重置，也没有走 setZoomAndCenter
  const after = await readFakeAMap(page);
  expect(after.setZoomAndCenterCalls).toBe(0);
  expect(after.setCenterCalls).toBe(1);
  expect(after.zoom).toBe(before.zoom);
  // 真的定位到这条安排上（WGS-84 → GCJ-02 的偏移在 0.02° 量级）
  expect(Math.abs((after.center?.[0] ?? 0) - target.lng)).toBeLessThan(0.02);
  expect(Math.abs((after.center?.[1] ?? 0) - target.lat)).toBeLessThan(0.02);

  // ③ 来源标签两侧都在：地图图例 + 标记自身，时间线转场行
  const legend = page.getByTestId('map-legend');
  await expect(legend).toContainText('实测 · amap');
  await expect(legend).toContainText('估算 · estimated');
  await expect(marker.locator('.m3d-src')).toHaveAttribute('data-source', 'amap');
  await expect(page.locator('.t-leg[data-source="amap"]')).toContainText('实测 · amap');
  await expect(page.locator('.t-leg[data-source="estimated"]')).toContainText('估算 · estimated');

  fs.mkdirSync(EVIDENCE_DIR, { recursive: true });
  await page.screenshot({ path: path.join(EVIDENCE_DIR, '01-timeline-to-map.png') });
  console.log(`[evidence] 时间线 → 地图：${path.join(EVIDENCE_DIR, '01-timeline-to-map.png')}`);
});

test('点地图标记 → 时间线选中并滚动到 #ti-<id>，跨 Day 会先切到那一天', async ({ page }) => {
  const trip = await seedTrip(page);
  await stubRouteLegs(page, trip);
  await installFakeAMap(page);
  await openTrip(page, trip);

  const scroller = page.locator('.trip-timeline-card .tl-scroll');
  await scroller.evaluate((el) => {
    el.scrollTop = 0;
  });
  const last = trip.day1[trip.day1.length - 1];
  const lastRow = page.locator(`#ti-${last.id}`);
  await expect(lastRow).toHaveCount(1);
  // 前提：这一行此刻在滚动容器外面，否则「滚动过去」这条断言没有信息量
  const [rowBox, scrollBox] = [await lastRow.boundingBox(), await scroller.boundingBox()];
  expect(rowBox).not.toBeNull();
  expect(scrollBox).not.toBeNull();
  expect(rowBox!.y).toBeGreaterThan(scrollBox!.y + scrollBox!.height - 1);
  expect(await scroller.evaluate((el) => el.scrollTop)).toBe(0);

  await page.locator(`.m3d-marker[data-item-id="${last.id}"]`).click();

  // ① 回写同一个选中源：时间线那一行高亮，地图标记也是选中的
  await expect(lastRow).toHaveClass(/selected/);
  await expect(page.locator(`.m3d-marker[data-item-id="${last.id}"]`)).toHaveAttribute(
    'data-selected',
    'true',
  );

  // ② 真的滚过去了：容器被滚下来了，且那一行落在容器可视区内
  await expect.poll(() => scroller.evaluate((el) => el.scrollTop)).toBeGreaterThan(0);
  await expect
    .poll(async () => {
      const row = await lastRow.boundingBox();
      const box = await scroller.boundingBox();
      if (!row || !box) return false;
      return row.y >= box.y - 1 && row.y + row.height <= box.y + box.height + 1;
    })
    .toBe(true);

  // ③ 跨 Day：Day 2 的条目在 Day 1 选中时没有对应时间线行（不存在就是不存在，不造假行）
  await expect(page.locator(`#ti-${trip.day2.id}`)).toHaveCount(0);
  await page.locator(`.m3d-marker[data-item-id="${trip.day2.id}"]`).click();
  await expect(page.locator('.day-tabs .ant-segmented-item-selected')).toContainText('Day 2');
  await expect(page.locator(`#ti-${trip.day2.id}`)).toHaveClass(/selected/);
  await expect(page.locator('.m3d-marker[data-item-id="' + trip.day2.id + '"]')).toHaveAttribute(
    'data-selected',
    'true',
  );

  // ④ 联动之后来源标签仍两侧都在（跨 Day 后地图图例不会消失）
  await expect(page.getByTestId('map-legend')).toContainText('实测 · amap');
  await expect(page.getByTestId('map-legend')).toContainText('估算 · estimated');

  fs.mkdirSync(EVIDENCE_DIR, { recursive: true });
  await page.screenshot({ path: path.join(EVIDENCE_DIR, '02-map-to-timeline.png') });
  console.log(`[evidence] 地图 → 时间线：${path.join(EVIDENCE_DIR, '02-map-to-timeline.png')}`);
});
