// @vitest-environment happy-dom
import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { App } from 'antd';
import ChatPanel from './index';
import { useAgentStore } from '../../store/agentStore';
import type { ActivityEvent } from '../../store/stageText';
import type { ExecutionModule, ExecutionSummary } from '../../types/agent';

/**
 * 只桩掉网络层：发送链路在用例里被替换成 store 上的 spy，不触网也不产生真实 SSE。
 * vi.hoisted 保证工厂函数在 import 之前就能拿到 mock 引用。
 */
const api = vi.hoisted(() => ({
  retryExecution: vi.fn(),
  getRuns: vi.fn(),
  getRunDetail: vi.fn(),
}));

vi.mock('../../api/agent', () => ({
  retryExecution: api.retryExecution,
  getRuns: api.getRuns,
  getRunDetail: api.getRunDetail,
  getExecution: vi.fn(),
  confirmPlan: vi.fn(),
  cancelPlan: vi.fn(),
  getPendingPlan: vi.fn(),
  clearConversation: vi.fn(),
  streamAgentChat: vi.fn(),
  streamAgentChatPost: vi.fn(),
}));

const realSend = useAgentStore.getState().send;

afterEach(() => {
  cleanup();
  useAgentStore.setState({
    messages: [],
    streaming: false,
    stage: null,
    activity: [],
    lastExecution: null,
    send: realSend,
  });
  api.retryExecution.mockReset();
  api.getRuns.mockReset();
  api.getRunDetail.mockReset();
});

function ev(event: string, data: any, time = Date.now() - 1000): ActivityEvent {
  return { event, data, time };
}

function mod(patch: Partial<ExecutionModule>): ExecutionModule {
  return { module: 'calendar', status: 'SUCCEEDED', ...patch };
}

function execution(patch: Partial<ExecutionSummary> = {}): ExecutionSummary {
  return {
    executionId: 'exec-1',
    planId: 'plan-1',
    tripId: 7,
    status: 'PARTIAL_FAILED',
    modules: [
      mod({ module: 'calendar', detail: '已写入 8 个时间块' }),
      mod({ module: 'budget', status: 'FAILED', error: '预算服务超时' }),
    ],
    ...patch,
  };
}

/** antd App 提供 App.useApp() 的 message 上下文（与真实页面一致） */
function renderPanel() {
  return render(
    <App>
      <ChatPanel />
    </App>,
  );
}

describe('ChatPanel · V2.2 过程时间线与同步结果面板', () => {
  it('有 activity 时在 .chat-messages 内部渲染过程时间线', () => {
    useAgentStore.setState({ activity: [ev('start', {}), ev('planning', {})] });
    const { container } = renderPanel();

    expect(screen.getByTestId('agent-activity-panel')).toBeTruthy();
    // 必须挂在消息区内部（listRef 仍指向 .chat-messages 容器）
    expect(
      container.querySelector('.chat-messages [data-testid="agent-activity-panel"]'),
    ).toBeTruthy();
    expect(screen.getByText('已连接 Agent…')).toBeTruthy();
    expect(screen.getByText('正在规划行程…')).toBeTruthy();
  });

  it('无 activity 时不渲染过程时间线，消息区退化为既有问候 + 单行 stage', () => {
    useAgentStore.setState({ activity: [] });
    const { container } = renderPanel();

    expect(screen.queryByTestId('agent-activity-panel')).toBeNull();
    expect(container.querySelector('.chat-bubble.assistant')).toBeTruthy();
  });

  it('输入消息后点发送按钮调用 store.send（带当前行程/日期上下文）', () => {
    const send = vi.fn();
    useAgentStore.setState({ send });
    const { container } = renderPanel();

    fireEvent.change(screen.getByPlaceholderText(/输入旅行需求/), {
      target: { value: '  帮我规划上海三日游  ' },
    });

    const sendBtn = container.querySelector(
      '.chat-input-area button.ant-btn-primary',
    ) as HTMLButtonElement;
    expect(sendBtn).toBeTruthy();
    expect(sendBtn.disabled).toBe(false);
    fireEvent.click(sendBtn);

    expect(send).toHaveBeenCalledTimes(1);
    expect(send).toHaveBeenCalledWith(
      expect.objectContaining({ message: '帮我规划上海三日游', tripId: null }),
    );
  });

  it('lastExecution 存在时在消息区之后渲染同步结果面板', () => {
    useAgentStore.setState({ lastExecution: execution() });
    const { container } = renderPanel();

    expect(screen.getByTestId('execution-result-panel')).toBeTruthy();
    const panel = container.querySelector('.exec-panel') as HTMLElement;
    expect(panel).toBeTruthy();
    // 位置：紧跟 .chat-messages 之后、选中 chip 之前
    expect(panel.previousElementSibling?.classList.contains('chat-messages')).toBe(true);
    expect(container.querySelector('.chat-messages .exec-panel')).toBeNull();
  });

  it('点失败模块的重试按钮调用 retryExecution 并把新结果回写 store', async () => {
    const updated = execution({
      status: 'SUCCEEDED',
      modules: [mod({ module: 'calendar', detail: '已写入 8 个时间块' })],
    });
    api.retryExecution.mockResolvedValue(updated);
    useAgentStore.setState({ lastExecution: execution() });
    const { container } = renderPanel();

    fireEvent.click(container.querySelector('.em-retry-btn') as HTMLElement);

    await waitFor(() => expect(api.retryExecution).toHaveBeenCalledWith('exec-1'));
    await waitFor(() => expect(useAgentStore.getState().lastExecution?.status).toBe('SUCCEEDED'));
  });

  it('关闭同步结果面板后不再渲染', () => {
    useAgentStore.setState({ lastExecution: execution() });
    renderPanel();

    fireEvent.click(screen.getByRole('button', { name: '关闭同步结果面板' }));
    expect(screen.queryByTestId('execution-result-panel')).toBeNull();
  });

  it('运行记录入口打开抽屉并拉取最近运行（AgentRunsDrawer 自包含）', async () => {
    api.getRuns.mockResolvedValue([]);
    renderPanel();

    fireEvent.click(screen.getByRole('button', { name: '运行记录' }));

    await waitFor(() => expect(api.getRuns).toHaveBeenCalledTimes(1));
    await waitFor(() => expect(screen.getByTestId('agent-runs-drawer')).toBeTruthy());
  });
});