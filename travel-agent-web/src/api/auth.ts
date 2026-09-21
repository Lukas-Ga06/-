import { request } from './client';
import type { AuthResp } from '../types/trip';

export function loginApi(username: string, password: string): Promise<AuthResp> {
  return request<AuthResp>({ url: '/auth/login', method: 'post', data: { username, password } });
}

export function registerApi(username: string, password: string, nickname: string): Promise<AuthResp> {
  return request<AuthResp>({ url: '/auth/register', method: 'post', data: { username, password, nickname } });
}
