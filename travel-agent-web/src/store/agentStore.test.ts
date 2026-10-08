import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { AgentResult } from '../types/agent';
import type { AgentStreamHandlers, AgentStreamParams } from '../api/agent';

const { handlersRef, closeSpy } = vi.hoisted(() => ({
  handlersRef: { current: null as AgentStreamHandlers | null },
  closeSpy: vi.fn(),
}));

vi.mock('../api/agent', () => ({
  streamAgentChat: (_params: AgentStreamParams, handlers: AgentStreamHandlers) => {
    handlersRef.current = handlers;
    return Promise.resolve(closeSpy);
  },
  confirmPlan: vi.fn(),
  cancelPlan: vi.fn(),
}));

import { useAgentStore } from './agentStore';

function completedResult(overrides: Partial<AgentResult> = {}): AgentResult {
  return { status: 'COMPLETED', message: '行程已保存', ...overrides };
}

describe('agentStore', () => {
  beforeEach(() => {
    useAgentStore.getState().reset();
    handlersRef.current = null;
  });

  it('发送后先追加用户消息并进入 streaming 状态', () => {
    useAgentStore.getState().send({ message: '帮我安排一个上海三日游' });

    const state = useAgentStore.getState();
    expect(state.messages).toHaveLength(1);
    expect(state.messages[0]).toMatchObject({ role: 'user', content: '帮我安排一个上海三日游' });
    expect(state.streaming).toBe(true);
    expect(state.pendingConfirm).toBeNull();
  });

  it('streaming 期间重复发送会被忽略', () => {
    const store = useAgentStore.getState();
    store.send({ message: '第一条' });
    useAgentStore.getState().send({ message: '第二条' });

    expect(useAgentStore.getState().messages).toHaveLength(1);
  });

  it('空白消息不会发起请求', () => {
    useAgentStore.getState().send({ message: '   ' });
    expect(useAgentStore.getState().messages).toHaveLength(0);
    expect(useAgentStore.getState().streaming).toBe(false);
  });

  it('完成事件写入助手消息；新建行程时记录 committedTripId 供跳转', () => {
    useAgentStore.getState().send({ message: '安排上海三日游' });
    handlersRef.current?.onComplete?.(completedResult({ tripId: 99 }));

    const state = useAgentStore.getState();
    expect(state.messages).toHaveLength(2);
    expect(state.messages[1].role).toBe('assistant');
    expect(state.streaming).toBe(false);
    expect(state.committedTripId).toBe(99);
  });

  it('已有行程的修改不会触发跳转', () => {
    useAgentStore.getState().send({ message: '换掉明天的户外景点', tripId: 5 });
    handlersRef.current?.onComplete?.(completedResult({ tripId: 5 }));

    expect(useAgentStore.getState().committedTripId).toBeNull();
  });

  it('NEED_CONFIRMATION 结果会挂起待确认方案', () => {
    const pending = completedResult({
      status: 'NEED_CONFIRMATION',
      planId: 'plan-1',
      confirmation: { planId: 'plan-1', summary: '将新增 2 项', riskLevel: 'MEDIUM' },
    });
    useAgentStore.getState().send({ message: '安排上海三日游' });
    handlersRef.current?.onComplete?.(pending);

    expect(useAgentStore.getState().pendingConfirm?.planId).toBe('plan-1');
  });

  it('出错时以 ⚠ 前缀写入消息并结束 streaming', () => {
    useAgentStore.getState().send({ message: '安排上海三日游' });
    handlersRef.current?.onError?.('与 Agent 的连接已中断');

    const state = useAgentStore.getState();
    expect(state.streaming).toBe(false);
    expect(state.messages[1].content).toContain('与 Agent 的连接已中断');
    expect(state.messages[1].content).toContain('⚠');
  });

  it('reset 会清空会话状态并关闭连接', async () => {
    useAgentStore.getState().send({ message: '安排上海三日游' });
    useAgentStore.getState().reset();

    const state = useAgentStore.getState();
    expect(state.messages).toHaveLength(0);
    expect(state.streaming).toBe(false);
    // 票据获取是异步的：连接建立后才发现已被 reset，此时应立刻关闭
    await vi.waitFor(() => expect(closeSpy).toHaveBeenCalled());
  });

  it('restorePending 能把刷新后取回的方案重新挂起', () => {
    useAgentStore.getState().restorePending({
      status: 'NEED_CONFIRMATION',
      planId: 'plan-restored',
      message: '已生成方案，请确认',
      focus: { type: 'itinerary', day: 2 },
      confirmation: { planId: 'plan-restored', summary: '新增 2 项', riskLevel: 'MEDIUM' },
    });

    const state = useAgentStore.getState();
    expect(state.pendingConfirm?.planId).toBe('plan-restored');
    expect(state.lastFocus?.day).toBe(2);
  });
});
