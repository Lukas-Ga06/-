// @vitest-environment happy-dom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { App } from 'antd';
import ConversationDrawer from './index';
import { useAgentStore } from '../../store/agentStore';

/**
 * 最近会话抽屉（§8.6）：列表 → 继续对话 → 回填。
 * 只测「列表怎么来、点了发生什么」；消息渲染由 ChatPanel 自己的用例覆盖。
 */
const api = vi.hoisted(() => ({
  listConversations: vi.fn(),
  getConversationMessages: vi.fn(),
}));

vi.mock('../../api/agent', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../api/agent')>();
  return {
    ...actual,
    listConversations: api.listConversations,
    getConversationMessages: api.getConversationMessages,
  };
});

function renderDrawer(open = true) {
  const onClose = vi.fn();
  render(
    <App>
      <ConversationDrawer open={open} onClose={onClose} />
    </App>,
  );
  return { onClose };
}

beforeEach(() => {
  useAgentStore.setState({
    scope: 'assistant',
    conversationId: null,
    conversations: {},
    messages: [],
    streaming: false,
    pendingConfirm: null,
  });
  api.listConversations.mockResolvedValue([
    {
      conversationId: 'conv-1',
      title: '帮我规划杭州周末',
      lastMessageAt: '2026-11-20T10:00:00',
      messageCount: 4,
      scope: 'assistant',
    },
    {
      conversationId: 'conv-2',
      title: '记一下明天办签证',
      lastMessageAt: '2026-11-19T09:00:00',
      messageCount: 2,
      scope: 'trip:12',
    },
  ]);
  api.getConversationMessages.mockResolvedValue({
    conversationId: 'conv-1',
    scope: 'assistant',
    messages: [
      { role: 'user', content: '帮我规划杭州周末', createdAt: '2026-11-20T10:00:00' },
      { role: 'assistant', content: '好的，给你两版方案。', createdAt: '2026-11-20T10:00:05' },
    ],
  });
});

afterEach(() => {
  cleanup();
  api.listConversations.mockReset();
  api.getConversationMessages.mockReset();
});

describe('ConversationDrawer · 会话作用域（V21）', () => {
  it('列表里标出会话是在哪儿聊的，未绑定的存量会话不编造标签', async () => {
    api.listConversations.mockResolvedValue([
      {
        conversationId: 'conv-trip',
        title: '把第二天的酒店换掉',
        lastMessageAt: '2026-11-20T10:00:00',
        messageCount: 6,
        scope: 'trip:12',
      },
      {
        conversationId: 'conv-plan',
        title: '排一个三日游',
        lastMessageAt: '2026-11-19T10:00:00',
        messageCount: 2,
        scope: 'planner',
      },
      {
        conversationId: 'conv-legacy',
        title: '很久以前的对话',
        lastMessageAt: '2026-11-18T10:00:00',
        messageCount: 2,
        scope: null,
      },
    ]);
    renderDrawer();

    const tripRow = (await screen.findByText('把第二天的酒店换掉')).closest('button');
    expect(tripRow?.textContent).toContain('行程 #12');
    const planRow = screen.getByText('排一个三日游').closest('button');
    expect(planRow?.textContent).toContain('规划');
    const legacyRow = screen.getByText('很久以前的对话').closest('button');
    // 存量会话 scope 为 null：既不能显示「助手」，也不能显示上一段会话的标签
    expect(legacyRow?.textContent).not.toContain('行程 #');
    expect(legacyRow?.textContent).not.toContain('助手');
  });

  it('继续一段行程会话：把服务端记录的作用域恢复成行程绑定', async () => {
    api.getConversationMessages.mockResolvedValue({
      conversationId: 'conv-2',
      scope: 'trip:12',
      messages: [{ role: 'user', content: '把第二天的酒店换掉', createdAt: '2026-11-19T09:00:00' }],
    });
    renderDrawer();

    const item = await screen.findByText('记一下明天办签证');
    fireEvent.click(item.closest('button') as HTMLElement);

    await waitFor(() => expect(useAgentStore.getState().conversationId).toBe('conv-2'));
    expect(useAgentStore.getState().resumedTripId).toBe(12);
  });

  it('回填返回的作用域优先于列表副本（期间可能在别的标签页聊过）', async () => {
    api.getConversationMessages.mockResolvedValue({
      conversationId: 'conv-2',
      scope: 'assistant',
      messages: [
        { role: 'user', content: '顺便记一下明天办签证', createdAt: '2026-11-19T09:00:00' },
      ],
    });
    renderDrawer();

    const item = await screen.findByText('记一下明天办签证');
    fireEvent.click(item.closest('button') as HTMLElement);

    await waitFor(() => expect(useAgentStore.getState().conversationId).toBe('conv-2'));
    // 列表里写的是 trip:12，但最近的记录已改成 assistant —— 以回填为准，不恢复绑定
    expect(useAgentStore.getState().resumedTripId).toBeNull();
  });
});

describe('ConversationDrawer · 最近会话列表（§8.6）', () => {
  it('打开时拉取列表，展示标题、时间与条数', async () => {
    renderDrawer();

    expect(await screen.findByText('帮我规划杭州周末')).toBeTruthy();
    expect(screen.getByText('记一下明天办签证')).toBeTruthy();
    expect(screen.getByText(/4 条消息/)).toBeTruthy();
    expect(api.listConversations).toHaveBeenCalledTimes(1);
  });

  it('点一条会话：取回历史并切到该会话，随后关闭抽屉', async () => {
    const { onClose } = renderDrawer();
    const item = await screen.findByText('帮我规划杭州周末');
    fireEvent.click(item.closest('button') as HTMLElement);

    await waitFor(() => expect(useAgentStore.getState().conversationId).toBe('conv-1'));
    expect(api.getConversationMessages).toHaveBeenCalledWith('conv-1');
    expect(useAgentStore.getState().messages.map((m) => m.content)).toEqual([
      '帮我规划杭州周末',
      '好的，给你两版方案。',
    ]);
    expect(onClose).toHaveBeenCalled();
  });

  it('当前会话在列表里标出「当前会话」，避免用户重复点自己', async () => {
    useAgentStore.setState({ conversationId: 'conv-2' });
    renderDrawer();

    const active = await screen.findByText(/记一下明天办签证/);
    expect(active.closest('button')?.textContent).toContain('当前会话');
  });

  it('没有历史会话时给出空态，而不是空白抽屉', async () => {
    api.listConversations.mockResolvedValue([]);
    renderDrawer();

    expect(await screen.findByTestId('conversation-empty')).toBeTruthy();
    expect(screen.getByText('还没有历史会话')).toBeTruthy();
  });

  it('列表失败时展示错误与重试，重试成功后恢复列表', async () => {
    api.listConversations.mockRejectedValueOnce(new Error('会话列表加载失败'));
    renderDrawer();

    expect(await screen.findByRole('alert')).toBeTruthy();
    // 第一次失败后，重试拿到正常数据
    fireEvent.click(screen.getByText('重试').closest('button') as HTMLElement);
    expect(await screen.findByText('帮我规划杭州周末')).toBeTruthy();
    expect(api.listConversations).toHaveBeenCalledTimes(2);
  });

  it('关闭状态下不发请求（抽屉未打开不产生额外流量）', async () => {
    renderDrawer(false);

    await new Promise((resolve) => setTimeout(resolve, 20));
    expect(api.listConversations).not.toHaveBeenCalled();
  });
});
