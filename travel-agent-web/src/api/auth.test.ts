import { beforeEach, describe, expect, it, vi } from 'vitest';

const requestMock = vi.hoisted(() => vi.fn());
vi.mock('./client', () => ({ request: requestMock }));

import { deleteAccountApi, exportAccountDataApi } from './auth';

describe('auth api · 账号数据导出', () => {
  beforeEach(() => {
    requestMock.mockReset();
  });

  it('GET /auth/me/export 无参数：导出的身份由登录态决定，前端不传用户 id', async () => {
    const payload = { formatVersion: '1', counts: { trips: 0 } };
    requestMock.mockResolvedValue(payload);

    await expect(exportAccountDataApi()).resolves.toEqual(payload);

    expect(requestMock).toHaveBeenCalledTimes(1);
    expect(requestMock).toHaveBeenCalledWith({ url: '/auth/me/export' });
  });

  it('把 429 限流原样抛给调用方，由页面提示稍后重试', async () => {
    requestMock.mockRejectedValue(
      Object.assign(new Error('操作过于频繁，请 30 秒后再试'), { status: 429 }),
    );

    await expect(exportAccountDataApi()).rejects.toMatchObject({ status: 429 });
  });
});

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
