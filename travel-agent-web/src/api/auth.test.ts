import { beforeEach, describe, expect, it, vi } from 'vitest';

const requestMock = vi.hoisted(() => vi.fn());
vi.mock('./client', () => ({ request: requestMock }));

import { deleteAccountApi } from './auth';

describe('auth api · 注销账号', () => {
  beforeEach(() => {
    requestMock.mockReset();
  });

  it('DELETE /auth/me，body 传密码，并要求 401 不触发全局登出', async () => {
    requestMock.mockResolvedValue(undefined);

    await expect(deleteAccountApi('secret-123')).resolves.toBeUndefined();

    expect(requestMock).toHaveBeenCalledTimes(1);
    expect(requestMock).toHaveBeenCalledWith({
      url: '/auth/me',
      method: 'delete',
      data: { password: 'secret-123' },
      skipAuthRedirect: true,
    });
  });

  it('密码错误时把 401 原样抛给调用方，由页面展示内联错误', async () => {
    const error = Object.assign(new Error('密码错误'), { status: 401 });
    requestMock.mockRejectedValue(error);

    await expect(deleteAccountApi('wrong')).rejects.toMatchObject({
      status: 401,
      message: '密码错误',
    });
  });
});
