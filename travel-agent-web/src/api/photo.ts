import { request } from './client';
import { useAuthStore } from '../store/authStore';

export interface TripPhoto {
  id: number;
  tripId: number;
  originalName?: string | null;
  contentType?: string | null;
  sizeBytes?: number | null;
  createdAt?: string | null;
  url: string;
}

export const listPhotos = (tripId: number) => request<TripPhoto[]>({ url: `/trips/${tripId}/photos` });

export const deletePhoto = (id: number) => request<void>({ url: `/photos/${id}`, method: 'delete' });

export function uploadPhoto(tripId: number, file: File) {
  const form = new FormData();
  form.append('file', file);
  return request<TripPhoto>({
    url: `/trips/${tripId}/photos`,
    method: 'post',
    data: form,
  });
}

/**
 * 原图地址。浏览器的 <img> 无法自定义 Authorization 头，
 * 因此沿用 SSE 的做法把 token 放进查询参数（后端 JwtAuthenticationFilter 支持）。
 */
export function photoSrc(photo: TripPhoto): string {
  const token = useAuthStore.getState().token;
  return token ? `${photo.url}?token=${encodeURIComponent(token)}` : photo.url;
}
