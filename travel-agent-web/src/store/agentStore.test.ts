import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { AgentResult } from '../types/agent';
import type { AgentStreamHandlers, AgentStreamParams } from '../api/agent';
import { confirmPlan, cancelPlan } from '../api/agent';

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

import { tripIdOfScope, useAgentStore } from './agentStore';

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

  it('resumeConversation 装载历史消息并切到该会话（§8.6 继续对话）', () => {
    useAgentStore.getState().send({ message: '开一段新对话' });
    useAgentStore.getState().newConversation();
    const before = useAgentStore.getState().conversationId;

    useAgentStore.getState().resumeConversation('conv-history', [
      { role: 'user', content: '帮我规划杭州周末', createdAt: '2026-11-20T10:00:00' },
      { role: 'assistant', content: '好的，给你两版方案。', createdAt: '2026-11-20T10:00:05' },
    ]);

    const state = useAgentStore.getState();
    expect(state.conversationId).toBe('conv-history');
    expect(state.conversationId).not.toBe(before);
    expect(state.messages.map((m) => m.role)).toEqual(['user', 'assistant']);
    expect(state.messages[1].content).toBe('好的，给你两版方案。');
    // 回填的是「已结束」的历史：不能残留生成中状态
    expect(state.streaming).toBe(false);
    expect(state.pendingConfirm).toBeNull();
  });

  it('resumeConversation 回填后，作用域缓存也指向同一段会话（切走再切回不丢）', () => {
    useAgentStore.getState().setScope('assistant');
    useAgentStore
      .getState()
      .resumeConversation('conv-cache', [
        { role: 'user', content: '缓存里的问题', createdAt: null },
      ]);

    useAgentStore.getState().setScope('trip:9');
    expect(useAgentStore.getState().messages).toHaveLength(0);

    useAgentStore.getState().setScope('assistant');
    expect(useAgentStore.getState().conversationId).toBe('conv-cache');
    expect(useAgentStore.getState().messages[0].content).toBe('缓存里的问题');
  });

  it('send 携带当前作用域（V21）：服务端据此把会话归属记下来', () => {
    useAgentStore.getState().setScope('trip:12');
    useAgentStore.getState().send({ message: '把第二天的酒店换掉', tripId: 12 });
    expect(paramsRef.current?.scope).toBe('trip:12');

    useAgentStore.getState().setScope('planner');
    useAgentStore.getState().send({ message: '帮我排一个三日游' });
    expect(paramsRef.current?.scope).toBe('planner');
  });

  it('续聊恢复行程绑定：助手页继续一段行程会话时，下一条消息仍带 tripId（V21）', () => {
    useAgentStore.getState().setScope('assistant');
    useAgentStore
      .getState()
      .resumeConversation(
        'conv-trip',
        [{ role: 'user', content: '行程 12 第二天太赶了', createdAt: null }],
        'trip:12',
      );

    useAgentStore.getState().send({ message: '把第二天下午的景点删一个' });

    expect(useAgentStore.getState().resumedTripId).toBe(12);
    expect(paramsRef.current?.tripId).toBe(12);
    // 作用域仍是助手页：恢复的是行程绑定，不是页面归属
    expect(paramsRef.current?.scope).toBe('assistant');
  });

  it('页面自己带了 tripId 时以页面为准，不被历史绑定覆盖（V21）', () => {
    useAgentStore.getState().resumeConversation('conv-trip', [], 'trip:12');

    useAgentStore.getState().send({ message: '换成后天的', tripId: 30 });

    expect(paramsRef.current?.tripId).toBe(30);
  });

  it('未绑定的会话不回填 tripId；切换作用域或开新会话后绑定失效（V21）', () => {
    useAgentStore.getState().resumeConversation('conv-plain', [], null);
    expect(useAgentStore.getState().resumedTripId).toBeNull();
    useAgentStore.getState().send({ message: '你好' });
    expect(paramsRef.current?.tripId).toBeUndefined();

    useAgentStore.getState().resumeConversation('conv-trip', [], 'trip:7');
    expect(useAgentStore.getState().resumedTripId).toBe(7);
    // 切作用域：恢复的绑定属于上一段对话，不能泄漏到新页面
    useAgentStore.getState().setScope('trip:99');
    expect(useAgentStore.getState().resumedTripId).toBeNull();

    useAgentStore.getState().setScope('assistant');
    useAgentStore.getState().resumeConversation('conv-trip', [], 'trip:7');
    useAgentStore.getState().newConversation();
    expect(useAgentStore.getState().resumedTripId).toBeNull();
  });

  it('tripIdOfScope 只认 trip:<正整数>（V21）', () => {
    expect(tripIdOfScope('trip:12')).toBe(12);
    expect(tripIdOfScope('trip:0')).toBeNull();
    expect(tripIdOfScope('trip:-1')).toBeNull();
    expect(tripIdOfScope('trip:abc')).toBeNull();
    expect(tripIdOfScope('assistant')).toBeNull();
    expect(tripIdOfScope(null)).toBeNull();
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

describe('agentStore · 流隔离、停止生成与警告语义（阶段 A）', () => {
  const cancelPlanMock = vi.mocked(cancelPlan);

  function pendingResult(planId = 'plan-1'): AgentResult {
    return completedResult({
      status: 'NEED_CONFIRMATION',
      planId,
      confirmation: { planId, summary: '新增 2 项', riskLevel: 'MEDIUM' },
    });
  }

  beforeEach(() => {
    useAgentStore.getState().reset();
    useAgentStore.setState({ conversationId: null });
    handlersRef.current = null;
    paramsRef.current = null;
    cancelPlanMock.mockReset();
  });

  it('旧流的 complete 不能覆盖新会话（P0-02）', () => {
    useAgentStore.getState().send({ message: '第一轮' });
    const firstHandlers = handlersRef.current!;

    // 新请求取代旧流
    useAgentStore.getState().abort();
    useAgentStore.getState().send({ message: '第二轮' });
    const messagesBefore = useAgentStore.getState().messages.length;

    // 第一轮的迟到 complete / event 必须被丢弃
    firstHandlers.onEvent?.('intent', { intent: 'PLAN_TRIP' });
    firstHandlers.onComplete?.(completedResult({ message: '第一轮的旧结论' }));

    const state = useAgentStore.getState();
    expect(state.messages).toHaveLength(messagesBefore);
    expect(state.messages.some((m) => m.content.includes('第一轮的旧结论'))).toBe(false);
    expect(state.activity.map((e) => e.event)).not.toContain('intent');
    expect(state.streaming).toBe(true);
  });

  it('旧流的 error 同样不能解锁当前输入', () => {
    useAgentStore.getState().send({ message: '第一轮' });
    const firstHandlers = handlersRef.current!;
    useAgentStore.getState().abort();
    useAgentStore.getState().send({ message: '第二轮' });

    firstHandlers.onError?.('旧连接中断');

    expect(useAgentStore.getState().streaming).toBe(true);
    expect(useAgentStore.getState().messages.some((m) => m.content.includes('旧连接中断'))).toBe(
      false,
    );
  });

  it('过程警告只登记、不结束本轮生成', () => {
    useAgentStore.getState().send({ message: '安排上海三日游' });
    handlersRef.current?.onWarning?.({ message: '研究步骤预算耗尽', code: 'BUDGET_EXHAUSTED' });

    const state = useAgentStore.getState();
    expect(state.streaming).toBe(true);
    expect(state.warnings).toEqual(['研究步骤预算耗尽']);
    expect(state.activity.map((e) => e.event)).toContain('warning');

    // 警告之后仍然能正常拿到结论
    handlersRef.current?.onComplete?.(completedResult({ message: '部分完成' }));
    expect(useAgentStore.getState().streaming).toBe(false);
    expect(useAgentStore.getState().warnings).toHaveLength(1);
  });

  it('重复的同一警告只记一条', () => {
    useAgentStore.getState().send({ message: '安排' });
    handlersRef.current?.onWarning?.({ message: '天气服务不可用' });
    handlersRef.current?.onWarning?.({ message: '天气服务不可用' });
    expect(useAgentStore.getState().warnings).toEqual(['天气服务不可用']);
  });

  it('abort 立刻恢复输入、保留已获得的过程记录并写入「已停止」', () => {
    useAgentStore.getState().send({ message: '安排上海三日游' });
    handlersRef.current?.onEvent?.('run', { runId: 'run-1' });
    handlersRef.current?.onEvent?.('tool_result', { tool: 'weather' });

    useAgentStore.getState().abort();

    const state = useAgentStore.getState();
    expect(state.streaming).toBe(false);
    expect(state.stage).toBeNull();
    expect(state.messages[state.messages.length - 1].content).toContain('已停止生成');
    // 过程记录不能因为停止而消失
    expect(state.activity.map((e) => e.event)).toContain('tool_result');
    expect(state.activity.map((e) => e.event)).toContain('cancelled');
    // 停止后能继续发送
    useAgentStore.getState().send({ message: '换个方案' });
    expect(useAgentStore.getState().streaming).toBe(true);
  });

  it('stop 之后旧流的迟到事件一律丢弃', () => {
    useAgentStore.getState().send({ message: '安排' });
    const handlers = handlersRef.current!;
    useAgentStore.getState().abort();
    const snapshot = useAgentStore.getState().messages.length;

    handlers.onComplete?.(completedResult({ message: '迟到的结论' }));

    expect(useAgentStore.getState().messages).toHaveLength(snapshot);
  });

  it('未在生成中时 abort 是空操作', () => {
    useAgentStore.getState().abort();
    expect(useAgentStore.getState().messages).toHaveLength(0);
  });

  it('每条命令都带幂等键 requestId，且不同命令不复用（P0-01）', () => {
    useAgentStore.getState().send({ message: '记一下明天办签证' });
    const first = paramsRef.current?.requestId;
    expect(first).toBeTruthy();

    handlersRef.current?.onComplete?.(completedResult());
    useAgentStore.getState().send({ message: '再记一下后天订酒店' });
    const second = paramsRef.current?.requestId;

    expect(second).toBeTruthy();
    expect(second).not.toBe(first);
  });

  it('取消方案失败时保留 pendingConfirm，planId 不丢（P1-07）', async () => {
    useAgentStore.getState().send({ message: '安排' });
    handlersRef.current?.onComplete?.(pendingResult('plan-keep'));
    cancelPlanMock.mockRejectedValue(new Error('网络错误'));

    await useAgentStore.getState().cancel();

    const state = useAgentStore.getState();
    expect(state.pendingConfirm?.planId).toBe('plan-keep');
    expect(state.streaming).toBe(false);
    expect(state.messages[state.messages.length - 1].content).toContain('取消失败');

    // 服务端恢复后可以再次取消
    cancelPlanMock.mockResolvedValue(completedResult({ message: '已取消' }));
    await useAgentStore.getState().cancel();
    expect(useAgentStore.getState().pendingConfirm).toBeNull();
    expect(cancelPlanMock).toHaveBeenCalledTimes(2);
  });

  it('取消成功后才移除方案', async () => {
    useAgentStore.getState().send({ message: '安排' });
    handlersRef.current?.onComplete?.(pendingResult('plan-2'));
    cancelPlanMock.mockResolvedValue(completedResult({ message: '已取消' }));

    await useAgentStore.getState().cancel();

    expect(useAgentStore.getState().pendingConfirm).toBeNull();
    expect(cancelPlanMock).toHaveBeenCalledWith('plan-2');
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
        confirmation: {
          planId: 'plan-1',
          summary: '将新增 2 项',
          riskLevel: 'MEDIUM',
          contentHash: hash,
        },
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
          issues: [
            { type: 'BUDGET_OVERRUN', severity: 'HIGH', message: '计划合计 ¥3200 超过预算 ¥1' },
          ],
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

  it('确认遇到 409 时给出「此行程已在其他位置更新」，并且不丢弃待确认方案（阶段 D）', async () => {
    confirmPlanMock.mockRejectedValue(
      Object.assign(new Error('数据已被其他操作修改，请刷新后重试'), { status: 409 }),
    );
    useAgentStore.getState().send({ message: '安排' });
    handlersRef.current?.onComplete?.(
      completedResult({
        status: 'NEED_CONFIRMATION',
        planId: 'plan-3',
        confirmation: { planId: 'plan-3', summary: '新增 2 项', riskLevel: 'LOW' },
      }),
    );

    const result = await useAgentStore.getState().confirm();

    expect(result).toBeNull();
    // 冲突不是「服务端故障」：方案还在，用户刷新行程后可以重新确认
    expect(useAgentStore.getState().pendingConfirm?.planId).toBe('plan-3');
    expect(useAgentStore.getState().streaming).toBe(false);
    const messages = useAgentStore.getState().messages;
    const last = messages[messages.length - 1];
    expect(last?.role).toBe('assistant');
    expect(last?.content).toContain('此行程已在其他位置更新');
    expect(last?.content).toContain('本次提交未生效');
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

describe('agentStore · 会话作用域隔离（阶段 C）', () => {
  beforeEach(() => {
    useAgentStore.getState().reset();
    useAgentStore.setState({ scope: 'assistant', conversations: {}, conversationId: null });
    handlersRef.current = null;
  });

  it('不同作用域的消息互不可见，切回来能恢复原对话', () => {
    const store = useAgentStore.getState();
    store.setScope('assistant');
    store.send({ message: '助手里的问题' });
    handlersRef.current?.onComplete?.(completedResult({ message: '助手回答' }));
    const assistantCount = useAgentStore.getState().messages.length;
    expect(assistantCount).toBe(2);

    useAgentStore.getState().setScope('trip:1');
    expect(useAgentStore.getState().messages).toHaveLength(0);

    useAgentStore.getState().send({ message: '行程一的问题' });
    handlersRef.current?.onComplete?.(completedResult({ message: '行程一回答' }));

    useAgentStore.getState().setScope('assistant');
    const messages = useAgentStore.getState().messages;
    expect(messages).toHaveLength(assistantCount);
    expect(messages.some((m) => m.content === '助手回答')).toBe(true);
    expect(messages.some((m) => m.content === '行程一回答')).toBe(false);
  });

  it('每个作用域使用独立的会话标识（服务端历史不串）', () => {
    useAgentStore.getState().setScope('assistant');
    useAgentStore.getState().send({ message: 'a' });
    const assistantConversation = paramsRef.current?.conversationId;
    expect(assistantConversation).toBeTruthy();
    handlersRef.current?.onComplete?.(completedResult());

    useAgentStore.getState().setScope('trip:2');
    useAgentStore.getState().send({ message: 'b' });
    const tripConversation = paramsRef.current?.conversationId;
    expect(tripConversation).toBeTruthy();
    expect(tripConversation).not.toBe(assistantConversation);

    // 切回助手作用域后复用原来的会话标识
    handlersRef.current?.onComplete?.(completedResult());
    useAgentStore.getState().setScope('assistant');
    useAgentStore.getState().send({ message: 'c' });
    expect(paramsRef.current?.conversationId).toBe(assistantConversation);
  });

  it('切换作用域会丢弃在途流的迟到回调', () => {
    useAgentStore.getState().setScope('assistant');
    useAgentStore.getState().send({ message: '旧作用域的请求' });
    const staleHandlers = handlersRef.current;

    useAgentStore.getState().setScope('trip:3');
    staleHandlers?.onComplete?.(completedResult({ message: '迟到的回答' }));

    expect(useAgentStore.getState().messages).toHaveLength(0);
    expect(useAgentStore.getState().streaming).toBe(false);
  });
});

describe('agentStore · 结构化结果消息（阶段 C）', () => {
  beforeEach(() => {
    useAgentStore.getState().reset();
    useAgentStore.setState({ scope: 'assistant', conversations: {}, conversationId: null });
    handlersRef.current = null;
  });

  it('complete 把 AgentResult 写进助手消息，供结果卡渲染', () => {
    useAgentStore.getState().setScope('assistant');
    useAgentStore.getState().send({ message: '帮我规划' });
    handlersRef.current?.onComplete?.(
      completedResult({
        status: 'NEED_CONFIRMATION',
        message: '方案已生成',
        diff: { changes: [{ type: 'ADD', title: '外滩' }], budgetBefore: 0, budgetAfter: 1200 },
      }),
    );

    const assistant = useAgentStore.getState().messages.find((m) => m.role === 'assistant');
    expect(assistant?.result?.status).toBe('NEED_CONFIRMATION');
    expect(assistant?.result?.diff?.changes).toHaveLength(1);
  });

  it('abort 写入 CANCELLED 结果消息，输入立即恢复', () => {
    useAgentStore.getState().setScope('assistant');
    useAgentStore.getState().send({ message: '长任务' });
    expect(useAgentStore.getState().streaming).toBe(true);

    useAgentStore.getState().abort();

    const state = useAgentStore.getState();
    expect(state.streaming).toBe(false);
    const last = state.messages[state.messages.length - 1];
    expect(last.role).toBe('assistant');
    expect(last.result?.status).toBe('CANCELLED');
  });

  it('error 写入 FAILED 结果消息', () => {
    useAgentStore.getState().setScope('assistant');
    useAgentStore.getState().send({ message: '会失败的任务' });
    handlersRef.current?.onError?.('模型超时');

    const state = useAgentStore.getState();
    const last = state.messages[state.messages.length - 1];
    expect(last.result?.status).toBe('FAILED');
    expect(last.content).toContain('模型超时');
  });
});
