import { request } from './client';
import { useAuthStore } from '../store/authStore';
import type { AuthResp } from '../types/trip';

export function loginApi(username: string, password: string): Promise<AuthResp> {
  return request<AuthResp>({ url: '/auth/login', method: 'post', data: { username, password } });
}

export function registerApi(username: string, password: string, nickname: string): Promise<AuthResp> {
  return request<AuthResp>({ url: '/auth/register', method: 'post', data: { username, password, nickname } });
}

/** 凭 httpOnly Cookie 恢复登录态（页面刷新后内存里的 user 已丢失） */
export function meApi(): Promise<AuthResp> {
  return request<AuthResp>({ url: '/auth/me' });
}

/** 服务端登出：清掉浏览器里的登录 Cookie */
export function logoutApi(): Promise<void> {
  return request<void>({ url: '/auth/logout', method: 'post' });
}

/** UI 登出：清服务端 Cookie + 本地登录态；导航由调用方处理 */
export async function signOut(): Promise<void> {
  try {
    await logoutApi();
  } catch {
    // Cookie 可能已失效（401 会触发拦截器登出），本地照常登出
  }
  useAuthStore.getState().logout();
}
