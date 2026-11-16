import { useEffect, useState } from 'react';
import { useOnlineStatus } from '../../hooks/useOnlineStatus';

/**
 * 离线提示条：断网时置顶显示「离线：显示缓存数据」，恢复在线自动消失。
 * 数据来自 service worker 的 itinerary-* 运行时缓存（见 vite.config.ts）。
 */
export default function OfflineBanner() {
  const online = useOnlineStatus();
  const [dismissed, setDismissed] = useState(false);

  // 恢复在线后重置关闭状态：下次断网应该再提示一次，而不是永久静默
  useEffect(() => {
    if (online) setDismissed(false);
  }, [online]);

  if (online || dismissed) return null;

  return (
    <div className="offline-banner" role="status" aria-live="polite" data-testid="offline-banner">
      <span className="ob-dot" aria-hidden="true" />
      <span className="ob-text">离线：显示缓存数据</span>
      <button
        type="button"
        className="ob-close"
        aria-label="关闭离线提示"
        onClick={() => setDismissed(true)}
      >
        ✕
      </button>
    </div>
  );
}