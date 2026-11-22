// @vitest-environment happy-dom
import { beforeEach, describe, expect, it } from 'vitest';
import { USER_CACHE_KEY, useAuthStore } from './authStore';
import { useAgentStore } from './agentStore';
import { DRAFT_STORAGE_KEY } from '../pages/Planner/planDraft';

const demo = { id: 1, username: 'demo', nickname: '演示用户' };

describe('authStore · 离线兜底用户缓存（PWA 离线恢复）', () => {
  beforeEach(() => {
    localStorage.clear();
    useAuthStore.setState({ token: null, user: null, authChecked: false, sessionError: null });
  });

  it('setUser 把公开资料写入 localStorage，且不缓存 token', () => {
    useAuthStore.getState().setUser(demo);

    const raw = localStorage.getItem(USER_CACHE_KEY);
    expect(raw).not.toBeNull();
    expect(JSON.parse(raw!)).toEqual(demo);
    expect(raw).not.toContain('token');
  });

  it('setAuth 同样落缓存', () => {
    useAuthStore.getState().setAuth('jwt-token', demo);

    expect(JSON.parse(localStorage.getItem(USER_CACHE_KEY)!)).toEqual(demo);
  });

  it('restoreCachedUser 用缓存恢复 user（断网启动可继续渲染缓存页面）', () => {
    useAuthStore.getState().setUser(demo);
    useAuthStore.setState({ user: null, sessionError: '登录状态校验失败' });

    const restored = useAuthStore.getState().restoreCachedUser();

    expect(restored).toEqual(demo);
    expect(useAuthStore.getState().user).toEqual(demo);
    expect(useAuthStore.getState().sessionError).toBeNull();
  });

  it('没有缓存时返回 null，不伪造登录态', () => {
    expect(useAuthStore.getState().restoreCachedUser()).toBeNull();
    expect(useAuthStore.getState().user).toBeNull();
  });

  it('损坏的缓存视为没有缓存', () => {
    localStorage.setItem(USER_CACHE_KEY, '{not-json');

    expect(useAuthStore.getState().restoreCachedUser()).toBeNull();
  });

  it('logout 清掉缓存，避免换账号后离线看到上一个用户', () => {
    useAuthStore.getState().setUser(demo);

    useAuthStore.getState().logout();

    expect(localStorage.getItem(USER_CACHE_KEY)).toBeNull();
    expect(useAuthStore.getState().user).toBeNull();
  });

  it('logout 同时抹掉上一个人的对话与规划草稿（同标签页换账号不串数据）', () => {
    useAgentStore.setState({
      messages: [
        { id: 'm1', role: 'assistant', content: '上一个人的攻略', time: '2026-11-22T10:00:00Z' },
      ],
      conversationId: 'conv-previous',
    });
    localStorage.setItem(DRAFT_STORAGE_KEY, JSON.stringify({ version: 1 }));

    useAuthStore.getState().logout();

    expect(useAgentStore.getState().messages).toEqual([]);
    expect(useAgentStore.getState().conversationId).toBeNull();
    expect(localStorage.getItem(DRAFT_STORAGE_KEY)).toBeNull();
  });
});
