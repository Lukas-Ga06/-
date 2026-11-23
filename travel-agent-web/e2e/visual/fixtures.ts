import type { Page, Route } from '@playwright/test';

/**
 * 视觉回归的 API 桩：**不依赖后端、数据库或真实模型**。
 *
 * 为什么用桩而不是真后端：截图基线要能逐像素比较，任何随「今天」「随机 id」
 * 变化的数据都会让基线天天失效。这里把接口返回固定成一组可读的中文样例，
 * 时间由用例里的 `page.clock.setFixedTime()` 冻结，因此同一份代码任何时候
 * 截图都应当一致。
 *
 * 契约形状与 `src/types/*` 保持一致；页面若新增必填字段，先补这里再更新基线，
 * 否则截图会拍下错误边界（这本身也是一条有用的守门）。
 */

function ok(data: unknown) {
  return { status: 200, contentType: 'application/json', body: JSON.stringify({ code: 0, data }) };
}

const TRIPS = [
  {
    id: 1,
    name: '杭州周末',
    destination: '杭州',
    startDate: '2026-12-12',
    endDate: '2026-12-13',
    budget: 2400,
    partySize: 2,
    status: 'CONFIRMED',
    phase: 'UPCOMING',
    daysUntilStart: 21,
    createdAt: '2026-11-01T09:00:00',
  },
  {
    id: 2,
    name: '上海出差',
    destination: '上海',
    startDate: '2026-11-05',
    endDate: '2026-11-06',
    budget: 1800,
    partySize: 1,
    status: 'CONFIRMED',
    phase: 'PAST',
    daysUntilStart: null,
    createdAt: '2026-10-20T09:00:00',
  },
  {
    id: 3,
    name: '成都三日',
    destination: '成都',
    startDate: '2026-11-20',
    endDate: '2026-11-23',
    budget: 4200,
    partySize: 2,
    status: 'CONFIRMED',
    phase: 'ONGOING',
    daysUntilStart: null,
    createdAt: '2026-10-28T09:00:00',
  },
];

const ITEMS = [
  {
    id: 101,
    tripId: 1,
    dayIndex: 1,
    itemType: 'transport',
    title: '高铁 上海虹桥 → 杭州东',
    startTime: '09:10',
    endTime: '10:05',
    latitude: 30.29,
    longitude: 120.21,
    cost: 73,
    tags: ['高铁'],
    description: null,
    status: 'COMPLETED',
    startedAt: '2026-12-12T09:10:00',
    completedAt: '2026-12-12T10:05:00',
    visitedAt: '2026-12-12T10:05:00',
  },
  {
    id: 102,
    tripId: 1,
    dayIndex: 1,
    itemType: 'attraction',
    title: '西湖 · 断桥残雪',
    startTime: '11:00',
    endTime: '12:30',
    latitude: 30.26,
    longitude: 120.15,
    cost: 0,
    tags: ['户外', '免费'],
    description: '沿白堤步行到孤山，午前光线最好。',
    status: 'IN_PROGRESS',
    startedAt: '2026-12-12T11:00:00',
    completedAt: null,
    visitedAt: null,
  },
  {
    id: 103,
    tripId: 1,
    dayIndex: 1,
    itemType: 'restaurant',
    title: '楼外楼（孤山店）',
    startTime: '12:40',
    endTime: '13:40',
    latitude: 30.25,
    longitude: 120.14,
    cost: 180,
    tags: ['午餐', '杭帮菜'],
    description: null,
    status: 'PENDING',
    startedAt: null,
    completedAt: null,
    visitedAt: null,
  },
  {
    id: 104,
    tripId: 1,
    dayIndex: 2,
    itemType: 'hotel',
    title: '湖滨银泰附近民宿',
    startTime: '15:00',
    endTime: null,
    latitude: 30.25,
    longitude: 120.17,
    cost: 620,
    tags: ['住宿'],
    description: null,
    status: 'PENDING',
    startedAt: null,
    completedAt: null,
    visitedAt: null,
  },
];

const BUDGET_EXECUTION = {
  tripId: 1,
  tripName: '杭州周末',
  budget: 2400,
  planned: 873,
  actual: 253,
  remaining: 2147,
  variance: -620,
  executionRate: 0.29,
  overBudget: false,
  planOverBudget: false,
  categories: [
    { category: '交通', planned: 146, actual: 73, over: false },
    { category: '餐饮', planned: 360, actual: 180, over: false },
    { category: '住宿', planned: 620, actual: 0, over: false },
  ],
  daily: [
    { dayIndex: 1, planned: 253, actual: 253 },
    { dayIndex: 2, planned: 620, actual: 0 },
  ],
};

const ROUTE_LEGS = [
  {
    dayIndex: 1,
    sequence: 1,
    fromItemId: 101,
    toItemId: 102,
    fromTitle: '高铁 上海虹桥 → 杭州东',
    toTitle: '西湖 · 断桥残雪',
    fromLatitude: 30.29,
    fromLongitude: 120.21,
    toLatitude: 30.26,
    toLongitude: 120.15,
    distanceKm: 5.2,
    walkMinutes: 68,
    driveMinutes: 16,
    taxiCost: 22,
    source: 'estimated',
  },
];

/** 把 /api/** 全部接管；未列出的路径返回 data:null（调用方按缺失降级） */
export async function installApiMocks(page: Page): Promise<void> {
  await page.route('**/api/**', async (route: Route) => {
    const req = route.request();
    const url = new URL(req.url());
    const path = url.pathname.replace(/^\/api/, '');
    const method = req.method();

    if (method === 'GET' && path === '/auth/me') {
      return route.fulfill(ok({ id: 1, username: 'demo', nickname: '旅行者' }));
    }
    if (method === 'GET' && path === '/trips/map') {
      return route.fulfill(
        ok(
          TRIPS.map((trip, index) => ({
            tripId: trip.id,
            name: trip.name,
            destination: trip.destination,
            status: trip.status,
            startDate: trip.startDate,
            endDate: trip.endDate,
            budget: trip.budget,
            lat: [30.25, 31.23, 30.57][index],
            lng: [120.15, 121.47, 104.06][index],
            phase: trip.phase,
            plannedPlaces: 4,
            // 新用户基线：有行程计划、还没有实际打卡足迹
            visitedPlaces: 0,
            visited: false,
            actualRoute: [],
          })),
        ),
      );
    }
    if (method === 'GET' && path === '/trips') {
      return route.fulfill({
        ...ok(TRIPS),
        body: JSON.stringify({
          code: 0,
          data: TRIPS,
          pagination: { total: TRIPS.length, limit: 20, offset: 0, hasMore: false },
        }),
      });
    }
    if (method === 'GET' && path === '/trips/1') return route.fulfill(ok(TRIPS[0]));
    if (method === 'GET' && path === '/trips/1/itinerary') return route.fulfill(ok(ITEMS));
    if (method === 'GET' && path === '/trips/1/route-legs') return route.fulfill(ok(ROUTE_LEGS));
    if (method === 'GET' && path === '/trips/1/budget-execution') {
      return route.fulfill(ok(BUDGET_EXECUTION));
    }
    if (method === 'GET' && path === '/trips/1/review') {
      return route.fulfill(
        ok({
          tripId: 1,
          name: '杭州周末',
          destination: '杭州',
          startDate: '2026-12-12',
          endDate: '2026-12-13',
          totalDays: 2,
          phase: 'UPCOMING',
          totalItems: 4,
          completedItems: 1,
          skippedItems: 0,
          inProgressItems: 1,
          pendingItems: 2,
          completionRate: 0.25,
          plannedCost: 873,
          actualCost: 253,
          budget: 2400,
          variance: -620,
          overBudget: false,
          plannedPlaces: [],
          visitedPlaces: [],
          actualRoute: [],
          photoCount: 0,
          photos: [],
          events: [],
          modifications: [],
          memoCount: 0,
          scheduleCount: 0,
          aiSummary: null,
        }),
      );
    }
    if (method === 'GET' && path === '/expenses/overview') {
      return route.fulfill(
        ok({
          monthSpent: 860,
          byTrip: [{ tripId: 1, name: '杭州周末', spent: 860, budget: 2400 }],
        }),
      );
    }
    if (method === 'GET' && path === '/memos') {
      return route.fulfill(
        ok([
          { id: 1, content: '记得带充电宝', done: false, createdAt: '2026-11-20T08:00:00' },
          { id: 2, content: '提前值机', done: false, createdAt: '2026-11-21T08:00:00' },
        ]),
      );
    }
    if (method === 'GET' && path === '/schedule') return route.fulfill(ok([]));
    if (method === 'GET' && path === '/reminders') {
      return route.fulfill(
        ok({
          items: [],
          count: 0,
          serverTime: '2026-11-21T10:00:00',
        }),
      );
    }
    if (method === 'GET' && path === '/agent/runs') return route.fulfill(ok([]));
    if (method === 'GET' && path === '/system/status') {
      return route.fulfill(
        ok({
          llmConfigured: true,
          llmVerified: true,
          llmModel: 'deepseek-flash',
          llmHint: '已配置真实模型',
          amapConfigured: false,
          geoProvider: 'estimated',
          geoHint: '未配置地图 Key，使用本地估算',
          timezone: 'Asia/Shanghai',
          routeProvider: 'estimated',
          routeConfigured: false,
          routeHint: '未配置路线 Provider',
        }),
      );
    }
    if (method === 'GET' && path.startsWith('/knowledge')) return route.fulfill(ok([]));
    if (method === 'GET' && path.startsWith('/agent/plans/')) return route.fulfill(ok(null));

    // 其余接口一律「无数据」：页面必须走空状态，而不是崩到错误边界
    return route.fulfill(ok(null));
  });
}
