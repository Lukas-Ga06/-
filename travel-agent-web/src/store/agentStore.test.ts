import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { AgentResult } from '../types/agent';
import type { AgentStreamHandlers, AgentStreamParams } from '../api/agent';
import { confirmPlan } from '../api/agent';

const { handlersRef, paramsRef, closeSpy, clearConversationSpy } = vi.hoisted(() => ({
  handlersRef: { current: null as AgentStreamHandlers | null },
  paramsRef: { current: null as AgentStreamParams | null },
  closeSpy: vi.fn(),
  clearConversationSpy: vi.fn(),
}));

vi.mock('../api/agent', () => ({
  streamAgentChat: (params: AgentStreamParams, handlers: AgentStreamHandlers) => {
    paramsRef.current = params;
    handlersRef.current = handlers;
    return Promise.resolve(closeSpy);
  },
  confirmPlan: vi.fn(),
  cancelPlan: vi.fn(),
  clearConversation: clearConversationSpy,
}));

import { useAgentStore } from './agentStore';

function completedResult(overrides: Partial<AgentResult> = {}): AgentResult {
  return { status: 'COMPLETED', message: '行程已保存', ...overrides };
}

describe('agentStore', () => {
  beforeEach(() => {
    useAgentStore.getState().reset();
    useAgentStore.setState({ conversationId: null });
    handlersRef.current = null;
    paramsRef.current = null;
    clearConversationSpy.mockClear();
  });

  it('发送后先追加用户消息并进入 streaming 状态', () => {
    useAgentStore.getState().send({ message: '帮我安排一个上海三日游' });

    const state = useAgentStore.getState();
    expect(state.messages).toHaveLength(1);
    expect(state.messages[0]).toMatchObject({ role: 'user', content: '帮我安排一个上海三日游' });
    expect(state.streaming).toBe(true);
    expect(state.pendingConfirm).toBeNull();
  });

  it('发送时携带会话标识：首次生成，后续复用', () => {
    useAgentStore.getState().send({ message: '第一条' });
    const firstId = useAgentStore.getState().conversationId;
    expect(firstId).toBeTruthy();
    expect(paramsRef.current?.conversationId).toBe(firstId);

    handlersRef.current?.onComplete?.(completedResult());
    useAgentStore.getState().send({ message: '第二条' });
    expect(useAgentStore.getState().conversationId).toBe(firstId);
    expect(paramsRef.current?.conversationId).toBe(firstId);
  });

  it('reset 只清界面消息，会话记忆保留（跨页面切换是同一对话）', () => {
    useAgentStore.getState().send({ message: '安排上海三日游' });
    const id = useAgentStore.getState().conversationId;

    useAgentStore.getState().reset();

    expect(useAgentStore.getState().messages).toHaveLength(0);
    expect(useAgentStore.getState().conversationId).toBe(id);
  });

  it('newConversation 换新会话标识并清掉服务端历史', () => {
    useAgentStore.getState().send({ message: '安排上海三日游' });
    const oldId = useAgentStore.getState().conversationId;

    useAgentStore.getState().newConversation();

    const state = useAgentStore.getState();
    expect(state.messages).toHaveLength(0);
    expect(state.conversationId).toBeTruthy();
    expect(state.conversationId).not.toBe(oldId);
    expect(clearConversationSpy).toHaveBeenCalledWith(oldId);
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

describe('agentStore · V2.2 运行追踪与执行同步', () => {
  const confirmPlanMock = vi.mocked(confirmPlan);

  beforeEach(() => {
    useAgentStore.getState().reset();
    useAgentStore.setState({ conversationId: null });
    handlersRef.current = null;
    paramsRef.current = null;
    clearConversationSpy.mockClear();
    confirmPlanMock.mockReset();
  });

  it('run 事件记录 currentRun，并把事件追加进 activity 时间线', () => {
    useAgentStore.getState().send({ message: '安排上海三日游' });
    handlersRef.current?.onEvent?.('run', { runId: 'run-abc-123' });
    handlersRef.current?.onEvent?.('intent', { intent: 'PLAN_TRIP' });

    const state = useAgentStore.getState();
    expect(state.currentRun).toEqual({ runId: 'run-abc-123' });
    expect(state.activity.map((e) => e.event)).toEqual(['run', 'intent']);
    expect(state.activity[0].data).toEqual({ runId: 'run-abc-123' });
    expect(typeof state.activity[0].time).toBe('number');
  });

  it('send 开始时清空上一轮的 activity 与 currentRun', () => {
    useAgentStore.getState().send({ message: '第一条' });
    handlersRef.current?.onEvent?.('run', { runId: 'r1' });
    handlersRef.current?.onComplete?.(completedResult());
    expect(useAgentStore.getState().activity.length).toBeGreaterThan(0);

    useAgentStore.getState().send({ message: '第二条' });
    const state = useAgentStore.getState();
    expect(state.activity).toHaveLength(0);
    expect(state.currentRun).toBeNull();
  });

  it('activity 只保留最近 40 条（密集事件窗口化）', () => {
    useAgentStore.getState().send({ message: '安排' });
    for (let i = 0; i < 45; i++) {
      handlersRef.current?.onEvent?.('tool_result', { tool: `t${i}` });
    }
    const activity = useAgentStore.getState().activity;
    expect(activity).toHaveLength(40);
    expect(activity[0].data).toEqual({ tool: 't5' });
    expect(activity[39].data).toEqual({ tool: 't44' });
  });

  it('complete 与 error 终止事件也会收进时间线', () => {
    useAgentStore.getState().send({ message: '安排' });
    handlersRef.current?.onComplete?.(completedResult());
    expect(useAgentStore.getState().activity.map((e) => e.event)).toContain('complete');

    useAgentStore.getState().send({ message: '再安排' });
    handlersRef.current?.onError?.('与 Agent 的连接已中断');
    expect(useAgentStore.getState().activity.map((e) => e.event)).toContain('error');
  });

  it('confirm 把待确认方案的 contentHash 回传，成功后保存 execution 结果', async () => {
    const hash = 'a'.repeat(32) + 'b'.repeat(32);
    confirmPlanMock.mockResolvedValue(
      completedResult({
        message: '已提交，日程已同步',
        execution: {
          executionId: 'exec-9',
          planId: 'plan-1',
          tripId: 7,
          status: 'PARTIAL_FAILED',
          modules: [
            { module: 'calendar', status: 'SUCCEEDED' },
            { module: 'budget', status: 'FAILED', error: '超时' },
          ],
        },
      }),
    );
    useAgentStore.getState().send({ message: '安排' });
    handlersRef.current?.onComplete?.(
      completedResult({
        status: 'NEED_CONFIRMATION',
        planId: 'plan-1',
        confirmation: { planId: 'plan-1', summary: '将新增 2 项', riskLevel: 'MEDIUM', contentHash: hash },
      }),
    );

    await useAgentStore.getState().confirm();

    expect(confirmPlanMock).toHaveBeenCalledTimes(1);
    expect(confirmPlanMock).toHaveBeenCalledWith('plan-1', hash);
    const state = useAgentStore.getState();
    expect(state.lastExecution?.executionId).toBe('exec-9');
    expect(state.lastExecution?.status).toBe('PARTIAL_FAILED');
    expect(state.lastExecution?.modules).toHaveLength(2);
    expect(state.pendingConfirm).toBeNull();
    const last = state.messages[state.messages.length - 1];
    expect(last.role).toBe('assistant');
    expect(last.content).toBe('已提交，日程已同步');
  });

  it('旧方案没有 contentHash 时不传指纹，execution 缺失时 lastExecution 置空', async () => {
    confirmPlanMock.mockResolvedValue(completedResult({ message: '已提交' }));
    useAgentStore.getState().send({ message: '安排' });
    handlersRef.current?.onComplete?.(
      completedResult({
        status: 'NEED_CONFIRMATION',
        planId: 'plan-2',
        confirmation: { planId: 'plan-2', summary: '调整 1 项', riskLevel: 'LOW' },
      }),
    );

    await useAgentStore.getState().confirm();

    expect(confirmPlanMock).toHaveBeenCalledWith('plan-2', undefined);
    expect(useAgentStore.getState().lastExecution).toBeNull();
  });

  it('confirm(true) 把 acknowledgeIssues 透传给确认接口（显式接受未解决风险）', async () => {
    const hash = 'c'.repeat(64);
    confirmPlanMock.mockResolvedValue(completedResult({ message: '已提交' }));
    useAgentStore.getState().send({ message: '安排' });
    handlersRef.current?.onComplete?.(
      completedResult({
        status: 'NEED_CONFIRMATION',
        planId: 'plan-1',
        review: {
          passed: false,
          blocked: true,
          attempts: 2,
          issues: [{ type: 'BUDGET_OVERRUN', severity: 'HIGH', message: '计划合计 ¥3200 超过预算 ¥1' }],
          repairs: [],
        },
        confirmation: {
          planId: 'plan-1',
          summary: '预算 1 元的行程',
          riskLevel: 'HIGH',
          contentHash: hash,
          requiresAcknowledgement: true,
        },
      }),
    );

    await useAgentStore.getState().confirm(true);

    expect(confirmPlanMock).toHaveBeenCalledWith('plan-1', hash, true);
  });

  it('setLastExecution 覆盖执行结果（重试成功后由面板回写）', () => {
    const next = {
      executionId: 'exec-9',
      planId: 'plan-1',
      status: 'SUCCEEDED' as const,
      modules: [
        { module: 'calendar' as const, status: 'SUCCEEDED' as const },
        { module: 'budget' as const, status: 'SUCCEEDED' as const },
        { module: 'footprint' as const, status: 'SUCCEEDED' as const },
      ],
    };
    useAgentStore.getState().setLastExecution(null);
    useAgentStore.getState().setLastExecution(next);
    expect(useAgentStore.getState().lastExecution?.status).toBe('SUCCEEDED');
  });
});
