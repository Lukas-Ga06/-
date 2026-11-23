// @vitest-environment happy-dom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { App } from 'antd';
import { MemoryRouter } from 'react-router-dom';
import Profile from './index';
import { useAuthStore } from '../../store/authStore';
import { useTripStore } from '../../store/tripStore';
import { ApiError } from '../../api/client';

const api = vi.hoisted(() => ({
  systemStatus: vi.fn(),
  deleteAccountApi: vi.fn(),
  exportAccountDataApi: vi.fn(),
  signOut: vi.fn(),
}));

vi.mock('../../api/assistant', () => ({ systemStatus: api.systemStatus }));
vi.mock('../../api/auth', () => ({
  deleteAccountApi: api.deleteAccountApi,
  exportAccountDataApi: api.exportAccountDataApi,
  signOut: api.signOut,
}));

vi.mock('../../components/AppShell', () => ({
  default: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
}));

const navigateMock = vi.hoisted(() => vi.fn());
vi.mock('react-router-dom', async (importOriginal) => {
  const actual = await importOriginal<typeof import('react-router-dom')>();
  return { ...actual, useNavigate: () => navigateMock };
});

const USER = { id: 1, username: 'demo', nickname: '演示用户' };

function renderPage() {
  return render(
    <MemoryRouter initialEntries={['/profile']}>
      <App>
        <Profile />
      </App>
    </MemoryRouter>,
  );
}

/** 打开注销弹窗、填密码并勾选确认 */
function fillDeleteForm(password = 'secret-123') {
  fireEvent.click(screen.getByTestId('open-delete-account'));
  fireEvent.change(screen.getByLabelText('当前密码'), { target: { value: password } });
  fireEvent.click(screen.getByRole('checkbox'));
}

beforeEach(() => {
  useAuthStore.setState({
    token: 'token-1',
    user: USER,
    authChecked: true,
    sessionError: null,
  });
  useTripStore.setState({
    trips: [],
    tripsLoading: false,
    tripsError: null,
    loadTrips: vi.fn().mockResolvedValue([]),
  });
  Object.values(api).forEach((fn) => fn.mockReset());
  navigateMock.mockReset();
  // happy-dom 没有 Blob URL 实现：导出流程只关心是否触发下载与释放
  Object.defineProperty(globalThis.URL, 'createObjectURL', {
    value: vi.fn(() => 'blob:mock'),
    writable: true,
    configurable: true,
  });
  Object.defineProperty(globalThis.URL, 'revokeObjectURL', {
    value: vi.fn(),
    writable: true,
    configurable: true,
  });
  api.systemStatus.mockResolvedValue({
    llmConfigured: true,
    llmModel: 'deepseek-chat',
    llmHint: '已配置',
    amapConfigured: false,
    geoHint: '未配置',
    timezone: 'Asia/Shanghai',
  });
});

afterEach(() => {
  cleanup();
});

describe('Profile · 导出数据（数据可携带）', () => {
  it('点击导出拉取本人数据并触发文件下载，登录态不变', async () => {
    api.exportAccountDataApi.mockResolvedValue({ formatVersion: '1', counts: { trips: 1 } });
    renderPage();

    fireEvent.click(screen.getByTestId('export-account-data'));

    await waitFor(() => expect(api.exportAccountDataApi).toHaveBeenCalledTimes(1));
    await waitFor(() => expect(globalThis.URL.createObjectURL).toHaveBeenCalledTimes(1));
    expect(globalThis.URL.revokeObjectURL).toHaveBeenCalledWith('blob:mock');
    // 导出是只读动作：不清登录态、不跳转
    expect(useAuthStore.getState().user).toEqual(USER);
    expect(navigateMock).not.toHaveBeenCalled();
  });

  it('限流 429 只提示稍后再试，不清登录态也不跳转', async () => {
    api.exportAccountDataApi.mockRejectedValue(new ApiError('操作过于频繁，请 30 秒后再试', 429));
    renderPage();

    fireEvent.click(screen.getByTestId('export-account-data'));

    await waitFor(() => expect(api.exportAccountDataApi).toHaveBeenCalledTimes(1));
    expect(useAuthStore.getState().user).toEqual(USER);
    expect(useAuthStore.getState().token).toBe('token-1');
    expect(navigateMock).not.toHaveBeenCalled();
  });

  it('导出进行中按钮进入 loading，避免重复点击放大服务端压力', async () => {
    let resolveExport: (value: unknown) => void = () => undefined;
    api.exportAccountDataApi.mockImplementation(
      () =>
        new Promise((resolve) => {
          resolveExport = resolve;
        }),
    );
    renderPage();

    fireEvent.click(screen.getByTestId('export-account-data'));

    const button = screen.getByTestId('export-account-data');
    await waitFor(() => expect(button.className).toContain('ant-btn-loading'));

    resolveExport({ formatVersion: '1', counts: {} });
    await waitFor(() => expect(button.className).not.toContain('ant-btn-loading'));
  });
});

describe('Profile · 注销账号（P0-D）', () => {
  it('危险区说明不可恢复，取消弹窗不发起请求、保持登录态', async () => {
    renderPage();

    expect(screen.getByText(/永久删除你的账号、全部行程、照片、会话/)).toBeTruthy();
    fireEvent.click(screen.getByTestId('open-delete-account'));

    expect(await screen.findByText(/当前账号/)).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: /取\s*消/ }));

    // happy-dom 下 Ant Modal 的关闭动画不会触发 motion end，只断言业务副作用：
    // 取消不应发起注销请求，也不应改变登录态。
    await waitFor(() => expect(api.deleteAccountApi).not.toHaveBeenCalled());
    expect(useAuthStore.getState().user).toEqual(USER);
  });

  it('密码为空时确认按钮不可提交，不发起请求', async () => {
    renderPage();

    fireEvent.click(screen.getByTestId('open-delete-account'));
    fireEvent.click(screen.getByRole('checkbox'));

    const confirm = screen.getByTestId('confirm-delete-account') as HTMLButtonElement;
    expect(confirm.disabled).toBe(true);
    fireEvent.click(confirm);

    expect(api.deleteAccountApi).not.toHaveBeenCalled();
  });

  it('密码错误（401）时内联提示并保持登录态，不跳登录页', async () => {
    api.deleteAccountApi.mockRejectedValue(new ApiError('密码错误', 401));
    renderPage();

    fillDeleteForm('wrong-password');
    fireEvent.click(screen.getByTestId('confirm-delete-account'));

    expect(await screen.findByTestId('delete-account-error')).toBeTruthy();
    expect(screen.getByTestId('delete-account-error').textContent).toContain('密码错误');
    expect(api.deleteAccountApi).toHaveBeenCalledWith('wrong-password');
    expect(useAuthStore.getState().user).toEqual(USER);
    expect(navigateMock).not.toHaveBeenCalled();
  });

  it('其他错误给出可重试文案，弹窗仍保留', async () => {
    api.deleteAccountApi.mockRejectedValue(new ApiError('数据库暂时不可用', 503));
    renderPage();

    fillDeleteForm();
    fireEvent.click(screen.getByTestId('confirm-delete-account'));

    expect(await screen.findByTestId('delete-account-error')).toBeTruthy();
    expect(screen.getByTestId('delete-account-error').textContent).toContain('数据库暂时不可用');
    expect(screen.queryByRole('dialog')).toBeTruthy();
    expect(useAuthStore.getState().user).toEqual(USER);
  });

  it('成功后清本地登录态并跳转 /login', async () => {
    api.deleteAccountApi.mockResolvedValue(undefined);
    renderPage();

    fillDeleteForm('secret-123');
    fireEvent.click(screen.getByTestId('confirm-delete-account'));

    await waitFor(() => expect(api.deleteAccountApi).toHaveBeenCalledWith('secret-123'));
    await waitFor(() => expect(navigateMock).toHaveBeenCalledWith('/login', { replace: true }));
    expect(useAuthStore.getState().user).toBeNull();
    expect(useAuthStore.getState().token).toBeNull();
    expect(api.signOut).not.toHaveBeenCalled();
  });
});
