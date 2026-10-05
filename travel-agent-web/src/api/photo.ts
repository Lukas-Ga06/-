import { request } from './client';

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
 * 因此带上短期只读票据（见 api/ticket.ts），而不是登录 JWT。
 */
export function photoSrc(photo: TripPhoto, ticket: string | null): string {
  return ticket ? `${photo.url}?ticket=${encodeURIComponent(ticket)}` : photo.url;
}
