// @vitest-environment happy-dom
import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen } from '@testing-library/react';
import { App } from 'antd';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import TripReviewPage from './index';
import type { TripReview } from '../../types/review';

const api = vi.hoisted(() => ({
  getTripReview: vi.fn(),
}));

vi.mock('../../api/trip', () => ({
  getTripReview: api.getTripReview,
}));

vi.mock('../../api/ticket', () => ({
  useAccessTicket: () => 'media-ticket',
  getAccessTicket: () => Promise.resolve('media-ticket'),
}));

vi.mock('../../api/reminder', () => ({
  getReminders: vi.fn(() =>
    Promise.resolve({ items: [], count: 0, serverTime: new Date().toISOString() }),
  ),
  dismissReminder: vi.fn(),
}));

afterEach(() => {
  cleanup();
  api.getTripReview.mockReset();
});

function review(patch: Partial<TripReview> = {}): TripReview {
  return {
    tripId: 5,
    name: '杭州周末',
    destination: '杭州',
    startDate: '2026-10-01',
    endDate: '2026-10-02',
    totalDays: 2,
    phase: 'PAST',
    totalItems: 4,
    completedItems: 2,
    skippedItems: 0,
    inProgressItems: 0,
    pendingItems: 2,
    completionRate: 0.5,
    plannedCost: 1200,
    actualCost: 1500,
    budget: 1400,
    variance: 300,
    overBudget: true,
    plannedPlaces: [
      {
        itemId: 1,
        dayIndex: 1,
        itemType: 'attraction',
        title: '西湖',
        status: 'COMPLETED',
        plannedAt: '2026-10-01T09:00:00',
        visitedAt: '2026-10-01T09:20:00',
        latitude: 30.24,
        longitude: 120.15,
      },
      {
        itemId: 2,
        dayIndex: 2,
        itemType: 'attraction',
        title: '灵隐寺',
        status: 'PENDING',
        plannedAt: '2026-10-02T09:00:00',
        visitedAt: null,
        latitude: 30.24,
        longitude: 120.1,
      },
    ],
    visitedPlaces: [
      {
        itemId: 1,
        dayIndex: 1,
        itemType: 'attraction',
        title: '西湖',
        status: 'COMPLETED',
        plannedAt: '2026-10-01T09:00:00',
        visitedAt: '2026-10-01T09:20:00',
        latitude: 30.24,
        longitude: 120.15,
      },
    ],
    actualRoute: [
      {
        itemId: 1,
        title: '西湖',
        visitedAt: '2026-10-01T09:20:00',
        latitude: 30.24,
        longitude: 120.15,
      },
    ],
    photoCount: 1,
    photos: [
      {
        id: 9,
        tripId: 5,
        originalName: 'west-lake.jpg',
        contentType: 'image/jpeg',
        sizeBytes: 1024,
        createdAt: '2026-10-01T10:00:00',
        url: '/api/photos/9/raw',
      },
    ],
    events: [
      {
        id: 11,
        itemId: 1,
        type: 'CHECK_IN',
        source: 'user',
        summary: '已完成：西湖',
        occurredAt: '2026-10-01T09:20:00',
      },
    ],
    modifications: [
      {
        id: 21,
        operationType: 'MANUAL_EDIT',
        reason: '手动新增行程项：西湖',
        at: '2026-10-01T08:00:00',
      },
    ],
    memoCount: 2,
    scheduleCount: 3,
    aiSummary: '这次杭州行完成了西湖打卡，实际支出略高于计划。',
    ...patch,
  };
}

function renderPage() {
  return render(
    <MemoryRouter initialEntries={['/trip/5/review']}>
      <App>
        <Routes>
          <Route path="/trip/:id/review" element={<TripReviewPage />} />
        </Routes>
      </App>
    </MemoryRouter>,
  );
}

describe('TripReview · 旅行回顾页', () => {
  it('展示完成率、计划/实际成本、超预算提示与真实到访', async () => {
    api.getTripReview.mockResolvedValue(review());

    renderPage();

    const completion = await screen.findByTestId('review-completion');
    expect(completion.textContent).toContain('50%');
    expect(completion.textContent).toContain('已完成 2');

    const cost = screen.getByTestId('review-cost');
    expect(cost.textContent).toContain('¥1200');
    expect(cost.textContent).toContain('¥1500');
    expect(cost.textContent).toContain('实际支出已超出预算');

    const visited = screen.getByTestId('review-visited-places');
    expect(visited.textContent).toContain('西湖');
    // 计划中的灵隐寺未打卡，绝不能出现在实际到访列表
    expect(visited.textContent).not.toContain('灵隐寺');
    expect(screen.getByTestId('review-planned-places').textContent).toContain('灵隐寺');

    const events = screen.getByTestId('review-events');
    expect(events.textContent).toContain('打卡');
    expect(events.textContent).toContain('来源：用户');

    expect(screen.getByTestId('review-ai-summary').textContent).toContain('实际支出略高于计划');
  });

  it('照片使用 media-read 票据拼接原图地址', async () => {
    api.getTripReview.mockResolvedValue(review());
    renderPage();

    const img = (await screen.findByAltText('west-lake.jpg')) as HTMLImageElement;
    expect(img.getAttribute('src')).toBe('/api/photos/9/raw?ticket=media-ticket');
  });

  it('接口失败展示错误与重试入口', async () => {
    api.getTripReview.mockRejectedValue(new Error('回顾服务不可用'));
    renderPage();

    expect(await screen.findByText('回顾服务不可用')).toBeTruthy();
    expect(screen.getByRole('button', { name: /重\s*试/ })).toBeTruthy();
  });
});
