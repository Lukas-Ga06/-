// @vitest-environment happy-dom
import { beforeEach, describe, expect, it } from 'vitest';
import { DEFAULT_SCOPE, useAgentStore } from './agentStore';
import { useTripStore } from './tripStore';
import { useUIStore } from './uiStore';
import { useStatusQueueStore } from './statusQueueStore';
import { clearClientSession } from './sessionCleanup';
import { DRAFT_STORAGE_KEY, DRAFT_VERSION } from '../pages/Planner/planDraft';
import { STATUS_QUEUE_KEY, readQueue } from '../lib/statusQueue';

/**
 * 登出/换账号的客户端清理契约。
 *
 * 这些断言对应一个真实缺陷：登出只清 authStore 时，同一标签页换账号登录，
 * 下一个人会看到上一个人的聊天记录、行程标题、跨页选中上下文，
 * 并被提示「恢复上次未完成规划？」（草稿是全局 key）。
 */
describe('clearClientSession · 登出必须抹掉上一个人的界面状态', () => {
  beforeEach(() => {
    localStorage.clear();
    useAgentStore.setState({
      scope: DEFAULT_SCOPE,
      messages: [],
      conversations: {},
      conversationId: null,
      pendingConfirm: null,
      activity: [],
      warnings: [],
      lastExecution: null,
      committedTripId: null,
      resumedTripId: null,
    });
    useTripStore.getState().reset();
    useUIStore.getState().reset();
    useStatusQueueStore.setState({ entries: [], hydrated: false, lastResults: [] });
  });

  function seedPreviousUser(): void {
    useAgentStore.setState({
      scope: 'trip:9',
      messages: [
        { id: 'm1', role: 'assistant', content: '上一个人的攻略', time: '2026-11-22T10:00:00Z' },
      ],
      conversations: { 'trip:9': 'conv-previous' },
      conversationId: 'conv-previous',
      pendingConfirm: { status: 'NEED_CONFIRMATION', planId: 'plan-1' } as never,
      committedTripId: 9,
    });
    useTripStore.setState({
      trips: [{ id: 9, name: '上一个人的行程' }] as never,
      currentTrip: { id: 9, name: '上一个人的行程' } as never,
      items: [{ id: 42, title: '外滩' }] as never,
      routeLegs: [{ from: 'A', to: 'B' }] as never,
    });
    useUIStore.setState({
      selectedDay: 3,
      selectedElement: { type: 'itinerary_item', id: 42, title: '外滩' } as never,
    });
    localStorage.setItem(
      DRAFT_STORAGE_KEY,
      JSON.stringify({ version: DRAFT_VERSION, form: {}, step: 1, updatedAt: 1 }),
    );
    useStatusQueueStore.getState().enqueue({
      tripId: 9,
      itemId: 42,
      status: 'COMPLETED',
      previousStatus: 'PENDING',
      title: '外滩',
    });
  }

  it('清空对话、待确认方案、会话标识与作用域', () => {
    seedPreviousUser();

    clearClientSession();

    const agent = useAgentStore.getState();
    expect(agent.messages).toEqual([]);
    expect(agent.pendingConfirm).toBeNull();
    expect(agent.conversationId).toBeNull();
    expect(agent.conversations).toEqual({});
    expect(agent.scope).toBe(DEFAULT_SCOPE);
    expect(agent.committedTripId).toBeNull();
  });

  it('清空行程列表/详情与跨页选中上下文', () => {
    seedPreviousUser();

    clearClientSession();

    const trip = useTripStore.getState();
    expect(trip.trips).toEqual([]);
    expect(trip.currentTrip).toBeNull();
    expect(trip.items).toEqual([]);
    expect(trip.routeLegs).toEqual([]);

    const ui = useUIStore.getState();
    expect(ui.selectedDay).toBe(1);
    expect(ui.selectedElement).toBeNull();
  });

  it('删掉全局规划草稿与离线待同步队列（否则会被下一个人恢复/重放）', () => {
    seedPreviousUser();
    expect(readQueue()).toHaveLength(1);

    clearClientSession();

    expect(localStorage.getItem(DRAFT_STORAGE_KEY)).toBeNull();
    expect(readQueue()).toEqual([]);
    expect(localStorage.getItem(STATUS_QUEUE_KEY)).not.toContain('外滩');
  });
});
