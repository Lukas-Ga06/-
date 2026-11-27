import type { ReactNode } from 'react';

export type ToastType = 'success' | 'warning' | 'error' | 'info' | 'ai';

export interface ToastItem {
  id: string;
  type: ToastType;
  title?: string;
  content: ReactNode;
  duration: number;
}

// 全局单例，不依赖 React Context（简化使用）
const listeners = new Set<(toasts: ToastItem[]) => void>();
let toastList: ToastItem[] = [];
let idCounter = 0;

function notify() {
  listeners.forEach((fn) => fn(toastList));
}

/** 订阅队列变化；返回取消订阅函数（给 ToastContainer 的 effect 直接用作 cleanup） */
export function subscribeToasts(fn: (toasts: ToastItem[]) => void): () => void {
  listeners.add(fn);
  return () => {
    listeners.delete(fn);
  };
}

export function dismiss(id: string) {
  toastList = toastList.filter((t) => t.id !== id);
  notify();
}

function push(toast: Omit<ToastItem, 'id' | 'duration'> & { duration?: number }) {
  const id = `toast-${++idCounter}`;
  const item: ToastItem = {
    id,
    duration: toast.duration ?? 3500,
    type: toast.type,
    title: toast.title,
    content: toast.content,
  };
  toastList = [...toastList, item];
  notify();

  if (item.duration > 0) {
    setTimeout(() => dismiss(id), item.duration);
  }
  return id;
}

export const toast = {
  success: (content: ReactNode, title?: string) => push({ type: 'success', content, title }),
  warning: (content: ReactNode, title?: string) => push({ type: 'warning', content, title }),
  error: (content: ReactNode, title?: string) => push({ type: 'error', content, title }),
  info: (content: ReactNode, title?: string) => push({ type: 'info', content, title }),
  ai: (content: ReactNode, title?: string) => push({ type: 'ai', content, title, duration: 5000 }),
  dismiss,
};
