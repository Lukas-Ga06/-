import { beforeEach, describe, expect, it, vi } from 'vitest';

/**
 * 会话管理接口（V23）：改名与删除。
 *
 * 只断言「请求怎么发」——URL、方法、请求体、编码规则。响应解析与错误处理由
 * `client.test.ts` 统一覆盖，这里不重复桩一套 HTTP 语义。
 *
 * 用普通函数而不是 vi.fn：vitest 的 restoreMocks 会在每个用例前重置 mock 实现
 * （与 agent.test.ts 的写法保持一致）。
 */
interface CapturedRequest {
  url?: string;
  method?: string;
  data?: unknown;
}

const calls: CapturedRequest[] = [];

vi.mock('./client', () => ({
  request: (config: CapturedRequest) => {
    calls.push(config);
    if (config.method === 'patch') {
      return Promise.resolve({ conversationId: 'conv-1', title: config.data ? 'X' : null });
    }
    return Promise.resolve(undefined);
  },
}));

import { deleteConversation, renameConversation } from './agent';

describe('会话管理接口（V23）', () => {
  beforeEach(() => {
    calls.length = 0;
  });

  it('renameConversation：PATCH 到会话资源，标题放请求体', async () => {
    await renameConversation('conv-1', '杭州两日游');

    expect(calls).toHaveLength(1);
    expect(calls[0]).toEqual({
      url: '/agent/conversations/conv-1',
      method: 'patch',
      data: { title: '杭州两日游' },
    });
  });

  it('renameConversation：空串也照发（空串=恢复自动标题，不能被前端悄悄过滤成「不提交」）', async () => {
    await renameConversation('conv-1', '');

    expect(calls[0].data).toEqual({ title: '' });
  });

  it('renameConversation：conversationId 进 URL 前做编码，不会把特殊字符拼进路径', async () => {
    await renameConversation('a b/c', '名字');

    expect(calls[0].url).toBe('/agent/conversations/a%20b%2Fc');
  });

  it('deleteConversation：DELETE 到会话资源', async () => {
    await deleteConversation('conv-2');

    expect(calls[0]).toEqual({ url: '/agent/conversations/conv-2', method: 'delete' });
  });
});
