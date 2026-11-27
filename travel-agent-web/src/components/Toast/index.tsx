import { useEffect, useState, useCallback, useRef, type ReactNode } from 'react';
import {
  CheckOutlined,
  CloseOutlined,
  ExclamationOutlined,
  InfoOutlined,
  StarOutlined,
} from '@ant-design/icons';
import { dismiss, subscribeToasts, type ToastItem, type ToastType } from './toastStore';

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
    return subscribeToasts((next) => setToasts([...next]));
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
