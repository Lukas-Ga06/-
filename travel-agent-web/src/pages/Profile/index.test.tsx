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
  signOut: vi.fn(),
}));

vi.mock('../../api/assistant', () => ({ systemStatus: api.systemStatus }));
vi.mock('../../api/auth', () => ({
  deleteAccountApi: api.deleteAccountApi,
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
