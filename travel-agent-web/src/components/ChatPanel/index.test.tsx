// @vitest-environment happy-dom
import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { App } from 'antd';
import { MemoryRouter } from 'react-router-dom';
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
const realAbort = useAgentStore.getState().abort;

afterEach(() => {
  cleanup();
  useAgentStore.setState({
    messages: [],
    streaming: false,
    stage: null,
    activity: [],
    warnings: [],
    lastExecution: null,
    send: realSend,
    abort: realAbort,
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

/** antd App 提供 App.useApp() 的 message 上下文（与真实页面一致）；Router 提供结果卡「打开行程」导航 */
function renderPanel() {
  return render(
    <MemoryRouter>
      <App>
        <ChatPanel />
      </App>
    </MemoryRouter>,
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
    fireEvent.click(screen.getByRole('button', { name: '查看运行详情' }));
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

describe('ChatPanel · 停止生成与过程警告（阶段 A）', () => {
  it('生成中展示可点击的「停止」按钮，点击调用 store.abort（P1-06）', () => {
    const abort = vi.fn();
    useAgentStore.setState({ streaming: true, stage: '正在规划行程…', abort });
    renderPanel();

    const stopBtn = screen.getByRole('button', { name: '停止生成' });
    expect((stopBtn as HTMLButtonElement).disabled).toBe(false);

    fireEvent.click(stopBtn);
    expect(abort).toHaveBeenCalledTimes(1);
  });

  it('生成中不再显示被禁用的发送按钮', () => {
    useAgentStore.setState({ streaming: true, stage: '正在规划行程…' });
    const { container } = renderPanel();

    // 旧实现：生成中把发送按钮换成关闭图标却保持 disabled，看起来能停其实不能
    expect(screen.queryByRole('button', { name: '发送' })).toBeNull();
    expect(container.querySelector('.chat-input-area button.ant-btn-primary')).toBeTruthy();
  });

  it('有过程警告时在结果旁显示「待核实」，点击停止后仍保留', () => {
    useAgentStore.setState({
      streaming: false,
      warnings: ['研究步骤预算耗尽', '天气服务不可用'],
    });
    renderPanel();

    expect(screen.getByText('⚠ 部分内容待核实')).toBeTruthy();
    expect(screen.getByText('研究步骤预算耗尽')).toBeTruthy();
    expect(screen.getByText('天气服务不可用')).toBeTruthy();
  });

  it('没有警告时不显示待核实区块', () => {
    useAgentStore.setState({ warnings: [] });
    renderPanel();

    expect(screen.queryByText('⚠ 部分内容待核实')).toBeNull();
  });
});

describe('ChatPanel · 空对话示例可点击（§8.6）', () => {
  it('示例是可点击按钮，点一下按当前上下文直接发送', () => {
    const send = vi.fn();
    useAgentStore.setState({ send, messages: [] });
    renderPanel();

    const list = screen.getByTestId('chat-suggestions');
    const buttons = list.querySelectorAll('button');
    expect(buttons.length).toBeGreaterThanOrEqual(3);

    fireEvent.click(buttons[0]);

    expect(send).toHaveBeenCalledTimes(1);
    expect(send).toHaveBeenCalledWith(expect.objectContaining({ message: buttons[0].textContent }));
  });

  it('生成中示例按钮禁用，避免并发发请求', () => {
    useAgentStore.setState({ streaming: true, stage: '正在规划行程…' });
    renderPanel();

    const buttons = screen.getByTestId('chat-suggestions').querySelectorAll('button');
    expect(Array.from(buttons).every((b) => (b as HTMLButtonElement).disabled)).toBe(true);
  });
});

describe('ChatPanel · 结构化结果卡与下一步建议（阶段 C）', () => {
  it('助手消息带 result 时渲染结果卡，而不是纯文本气泡', () => {
    useAgentStore.setState({
      messages: [
        { id: 'm1', role: 'user', content: '帮我规划上海三日游', time: '2026-11-20T10:00:00Z' },
        {
          id: 'm2',
          role: 'assistant',
          content: '方案已生成，请确认',
          time: '2026-11-20T10:00:01Z',
          result: {
            status: 'NEED_CONFIRMATION',
            message: '方案已生成，请确认',
            confirmation: { planId: 'p-1', summary: '上海三日游' },
          },
        },
      ],
    });

    renderPanel();

    expect(screen.getByTestId('result-card')).toBeTruthy();
    expect(screen.getByTestId('result-status').textContent).toBe('待确认');
    // 结果正文只在卡片里出现一次，气泡不再重复
    expect(screen.getAllByText('方案已生成，请确认')).toHaveLength(1);
  });

  it('待确认结果后给出上下文建议，点击按当前上下文发送', () => {
    const sendSpy = vi.fn();
    useAgentStore.setState({
      send: sendSpy,
      messages: [
        { id: 'm1', role: 'user', content: '帮我规划上海三日游', time: '2026-11-20T10:00:00Z' },
        {
          id: 'm2',
          role: 'assistant',
          content: '方案已生成',
          time: '2026-11-20T10:00:01Z',
          result: { status: 'NEED_CONFIRMATION', message: '方案已生成' },
        },
      ],
    });

    renderPanel();

    const followups = screen.getByTestId('chat-followups');
    expect(followups.textContent).toContain('预算再低一点');
    fireEvent.click(screen.getByRole('button', { name: '预算再低一点' }));
    expect(sendSpy).toHaveBeenCalledWith(
      expect.objectContaining({ message: '预算再低一点', tripId: null }),
    );
  });

  it('失败结果提供「重试上一次」，重发上一条用户消息', () => {
    const sendSpy = vi.fn();
    useAgentStore.setState({
      send: sendSpy,
      messages: [
        { id: 'm1', role: 'user', content: '把第二天的景点换掉', time: '2026-11-20T10:00:00Z' },
        {
          id: 'm2',
          role: 'assistant',
          content: '模型超时',
          time: '2026-11-20T10:00:01Z',
          result: { status: 'FAILED', message: '模型超时' },
        },
      ],
    });

    renderPanel();

    expect(screen.getByTestId('result-status').textContent).toBe('失败');
    fireEvent.click(screen.getByRole('button', { name: '重试上一次' }));
    expect(sendSpy).toHaveBeenCalledWith(
      expect.objectContaining({ message: '把第二天的景点换掉' }),
    );
  });

  it('生成中不显示下一步建议，避免并发发请求', () => {
    useAgentStore.setState({
      streaming: true,
      messages: [
        { id: 'm1', role: 'user', content: '帮我规划', time: '2026-11-20T10:00:00Z' },
        {
          id: 'm2',
          role: 'assistant',
          content: '方案已生成',
          time: '2026-11-20T10:00:01Z',
          result: { status: 'NEED_CONFIRMATION', message: '方案已生成' },
        },
      ],
    });

    renderPanel();

    expect(screen.queryByTestId('chat-followups')).toBeNull();
  });
});
