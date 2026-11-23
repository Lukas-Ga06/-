import { useEffect, useState, useCallback, useRef, type ReactNode } from 'react';
import {
  CheckOutlined,
  CloseOutlined,
  ExclamationOutlined,
  InfoOutlined,
  StarOutlined,
} from '@ant-design/icons';

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

function dismiss(id: string) {
  toastList = toastList.filter((t) => t.id !== id);
  notify();
}

export const toast = {
  success: (content: ReactNode, title?: string) => push({ type: 'success', content, title }),
  warning: (content: ReactNode, title?: string) => push({ type: 'warning', content, title }),
  error: (content: ReactNode, title?: string) => push({ type: 'error', content, title }),
  info: (content: ReactNode, title?: string) => push({ type: 'info', content, title }),
  ai: (content: ReactNode, title?: string) => push({ type: 'ai', content, title, duration: 5000 }),
  dismiss,
};

const ICONS: Record<ToastType, ReactNode> = {
  success: <CheckOutlined />,
  warning: <ExclamationOutlined />,
  error: <CloseOutlined />,
  info: <InfoOutlined />,
  ai: <StarOutlined />,
};

/**
 * Toast 通知容器：挂在根节点一次即可。
 * 用 toast.success() / toast.error() 等方法触发。
 */
export function ToastContainer() {
  const [toasts, setToasts] = useState<ToastItem[]>([]);

  useEffect(() => {
    const fn = (next: ToastItem[]) => setToasts([...next]);
    listeners.add(fn);
    return () => {
      listeners.delete(fn);
    };
  }, []);

  return (
    <div className="toast-container">
      {toasts.map((t) => (
        <ToastCard key={t.id} toast={t} onClose={() => dismiss(t.id)} />
      ))}
    </div>
  );
}

function ToastCard({ toast, onClose }: { toast: ToastItem; onClose: () => void }) {
  const [leaving, setLeaving] = useState(false);
  const timerRef = useRef<number | null>(null);

  const handleClose = useCallback(() => {
    setLeaving(true);
    setTimeout(onClose, 280);
  }, [onClose]);

  // 悬停暂停消失
  const onMouseEnter = () => {
    if (timerRef.current) clearTimeout(timerRef.current);
  };
  const onMouseLeave = () => {
    if (toast.duration > 0) {
      timerRef.current = window.setTimeout(handleClose, toast.duration);
    }
  };

  useEffect(() => {
    return () => {
      if (timerRef.current) clearTimeout(timerRef.current);
    };
  }, []);

  return (
    <div
      className={`toast-card toast-${toast.type} ${leaving ? 'leaving' : ''}`}
      onMouseEnter={onMouseEnter}
      onMouseLeave={onMouseLeave}
    >
      <div className="toast-icon" aria-hidden="true">
        {ICONS[toast.type]}
      </div>
      <div className="toast-body">
        {toast.title && <div className="toast-title">{toast.title}</div>}
        <div className="toast-content">{toast.content}</div>
      </div>
      <button className="toast-close" onClick={handleClose} aria-label="关闭" title="关闭">
        <CloseOutlined aria-hidden="true" />
      </button>
      {toast.duration > 0 && (
        <div className="toast-progress">
          <div
            className="toast-progress-bar"
            style={{ animationDuration: `${toast.duration}ms` }}
          />
        </div>
      )}
    </div>
  );
}
