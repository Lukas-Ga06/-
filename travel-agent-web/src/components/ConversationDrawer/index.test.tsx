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
  renameConversation: vi.fn(),
  deleteConversation: vi.fn(),
}));

vi.mock('../../api/agent', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../api/agent')>();
  return {
    ...actual,
    listConversations: api.listConversations,
    getConversationMessages: api.getConversationMessages,
    renameConversation: api.renameConversation,
    deleteConversation: api.deleteConversation,
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
  api.renameConversation.mockReset();
  api.deleteConversation.mockReset();
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

describe('ConversationDrawer · 改名与删除（V23）', () => {
  it('改名：内联输入 → 保存后按服务端结果重新拉列表', async () => {
    api.renameConversation.mockResolvedValue({ conversationId: 'conv-1', title: '杭州两日游' });
    renderDrawer();

    fireEvent.click(await screen.findByRole('button', { name: '重命名「帮我规划杭州周末」' }));
    const input = screen.getByLabelText('会话名称') as HTMLInputElement;
    expect(input.value).toBe('帮我规划杭州周末');

    fireEvent.change(input, { target: { value: '杭州两日游' } });
    // 第二次拉列表返回改名后的标题（服务端截断/折叠空白后可能和输入略有差异，一切以它为准）
    api.listConversations.mockResolvedValue([
      {
        conversationId: 'conv-1',
        title: '杭州两日游',
        lastMessageAt: '2026-11-20T10:00:00',
        messageCount: 4,
        scope: 'assistant',
      },
    ]);
    fireEvent.click(screen.getByRole('button', { name: /^保\s*存$/ }));

    await waitFor(() =>
      expect(api.renameConversation).toHaveBeenCalledWith('conv-1', '杭州两日游'),
    );
    expect(await screen.findByText('杭州两日游')).toBeTruthy();
    expect(api.listConversations).toHaveBeenCalledTimes(2);
  });

  it('改名传空串：交给服务端恢复自动标题，并如实提示', async () => {
    api.renameConversation.mockResolvedValue({ conversationId: 'conv-1', title: null });
    renderDrawer();

    fireEvent.click(await screen.findByRole('button', { name: '重命名「帮我规划杭州周末」' }));
    fireEvent.change(screen.getByLabelText('会话名称'), { target: { value: '   ' } });
    fireEvent.click(screen.getByRole('button', { name: /^保\s*存$/ }));

    await waitFor(() => expect(api.renameConversation).toHaveBeenCalledWith('conv-1', ''));
    expect(await screen.findByText(/已恢复自动标题/)).toBeTruthy();
  });

  it('取消改名：回到列表，不发任何请求', async () => {
    renderDrawer();

    fireEvent.click(await screen.findByRole('button', { name: '重命名「帮我规划杭州周末」' }));
    fireEvent.click(screen.getByRole('button', { name: /^取\s*消$/ }));

    await waitFor(() => expect(screen.queryByLabelText('会话名称')).toBeNull());
    expect(api.renameConversation).not.toHaveBeenCalled();
  });

  it('删除非当前会话：确认后从列表消失，不切换当前会话', async () => {
    useAgentStore.setState({ conversationId: 'conv-1' });
    api.deleteConversation.mockResolvedValue(undefined);
    renderDrawer();

    fireEvent.click(await screen.findByRole('button', { name: '删除「记一下明天办签证」' }));
    // Popconfirm 的确认按钮文案是「删除」，与行内图标按钮的 aria-label 区分开
    fireEvent.click(await screen.findByRole('button', { name: /^删\s*除$/ }));

    await waitFor(() => expect(api.deleteConversation).toHaveBeenCalledWith('conv-2'));
    expect(useAgentStore.getState().conversationId).toBe('conv-1');
  });

  it('删掉当前正在聊的会话：自动开一段新会话，不往已删除的历史里继续追加', async () => {
    useAgentStore.setState({ conversationId: 'conv-2' });
    api.deleteConversation.mockResolvedValue(undefined);
    renderDrawer();

    fireEvent.click(await screen.findByRole('button', { name: '删除「记一下明天办签证」' }));
    fireEvent.click(await screen.findByRole('button', { name: /^删\s*除$/ }));

    await waitFor(() => expect(api.deleteConversation).toHaveBeenCalledWith('conv-2'));
    await waitFor(() => expect(useAgentStore.getState().conversationId).not.toBe('conv-2'));
  });

  it('删除失败：给出提示且列表保持原样', async () => {
    api.deleteConversation.mockRejectedValue(new Error('网络开小差了'));
    renderDrawer();

    fireEvent.click(await screen.findByRole('button', { name: '删除「记一下明天办签证」' }));
    fireEvent.click(await screen.findByRole('button', { name: /^删\s*除$/ }));

    expect(await screen.findByText('网络开小差了')).toBeTruthy();
    expect(screen.getByText('记一下明天办签证')).toBeTruthy();
  });
});
