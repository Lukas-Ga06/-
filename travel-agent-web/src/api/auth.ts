import { request } from './client';
import { useAuthStore } from '../store/authStore';
import type { AuthResp } from '../types/trip';

export function loginApi(username: string, password: string): Promise<AuthResp> {
  return request<AuthResp>({ url: '/auth/login', method: 'post', data: { username, password } });
}

export function registerApi(
  username: string,
  password: string,
  nickname: string,
): Promise<AuthResp> {
  return request<AuthResp>({
    url: '/auth/register',
    method: 'post',
    data: { username, password, nickname },
  });
}

/** 凭 httpOnly Cookie 恢复登录态（页面刷新后内存里的 user 已丢失） */
export function meApi(): Promise<AuthResp> {
  return request<AuthResp>({ url: '/auth/me' });
}

/**
 * 注销账号：DELETE /auth/me，body 需要当前密码二次确认。
 *
 * 密码错误时后端返回 401。这不是「登录态失效」，必须标记 skipAuthRedirect，
 * 否则 401 拦截器会把正在注销的用户直接踢下线、跳登录页。
 * 删除成功由后端级联清理行程/照片/会话与派生数据并清 Cookie。
 */
export function deleteAccountApi(password: string): Promise<void> {
  return request<void>({
    url: '/auth/me',
    method: 'delete',
    data: { password },
    skipAuthRedirect: true,
  });
}

/** 服务端登出：清掉浏览器里的登录 Cookie */
export function logoutApi(): Promise<void> {
  return request<void>({ url: '/auth/logout', method: 'post' });
}

/** UI 登出：先清本地登录态，再把服务端注销当作 best-effort；导航由调用方处理 */
export async function signOut(): Promise<void> {
  // 本地身份必须立即失效：服务端注销慢或失败，都不能让界面停留在「已登录」
  useAuthStore.getState().logout();
  try {
    await logoutApi();
  } catch {
    // Cookie 可能已失效（401 会触发拦截器登出）或网络不可达，本地已登出即可
  }
}
