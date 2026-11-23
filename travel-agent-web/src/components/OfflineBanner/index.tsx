import { useEffect, useState } from 'react';
import { CloseOutlined } from '@ant-design/icons';
import { useOnlineStatus } from '../../hooks/useOnlineStatus';
import { usePendingSync, useStatusQueueStore } from '../../store/statusQueueStore';
import type { SyncResult } from '../../store/statusQueueStore';
import './offline-banner.css';

/** 逐项同步结果的摘要：成功几项、失败几项（失败时给出首次失败原因） */
function summarize(results: SyncResult[]): {
  ok: number;
  failed: number;
  firstError: string | null;
} {
  let ok = 0;
  let failed = 0;
  let firstError: string | null = null;
  for (const result of results) {
    if (result.ok) {
      ok += 1;
      continue;
    }
    failed += 1;
    if (!firstError) firstError = result.error ?? '未知原因';
  }
  return { ok, failed, firstError };
}

/**
 * 离线提示条 + 待同步队列状态（§7.3 第 4 条 / §10.3）。
 *
 * 三件事都在这一个全局位置做，因为行程页会被卸载，而队列不能：
 * 1. 挂载时 hydrate + 恢复在线后自动重放（`usePendingSync`）；
 * 2. 离线时明确告诉用户「本机已记录，还没写到服务器」；
 * 3. 重放完成后显示**逐项**结果，失败项保留并可手动重试。
 * 数据来自 service worker 的 itinerary-* 运行时缓存（见 vite.config.ts）。
 */
export default function OfflineBanner() {
  const online = useOnlineStatus();
  const entries = useStatusQueueStore((s) => s.entries);
  const syncing = useStatusQueueStore((s) => s.syncing);
  const lastResults = useStatusQueueStore((s) => s.lastResults);
  const clearResults = useStatusQueueStore((s) => s.clearResults);
  const flush = useStatusQueueStore((s) => s.flush);
  usePendingSync();

  const [dismissed, setDismissed] = useState(false);

  // 恢复在线后重置关闭状态：下次断网应该再提示一次，而不是永久静默
  useEffect(() => {
    if (online) setDismissed(false);
  }, [online]);

  // 同步成功的结果是一次性回执：看一眼就够，不占着屏幕
  const { ok, failed, firstError } = summarize(lastResults);
  useEffect(() => {
    if (syncing || failed > 0 || ok === 0) return;
    const timer = window.setTimeout(() => clearResults(), 4000);
    return () => window.clearTimeout(timer);
  }, [syncing, failed, ok, clearResults]);

  if (!online) {
    if (dismissed) return null;
    const pending = entries.length;
    return (
      <div className="offline-banner" role="status" aria-live="polite" data-testid="offline-banner">
        <span className="ob-dot" aria-hidden="true" />
        <span className="ob-text">
          离线：显示缓存数据
          {pending > 0 ? ` · ${pending} 项待同步` : ''}
        </span>
        <button
          type="button"
          className="ob-close"
          aria-label="关闭离线提示"
          title="关闭离线提示"
          onClick={() => setDismissed(true)}
        >
          <CloseOutlined aria-hidden="true" />
        </button>
      </div>
    );
  }

  if (syncing) {
    return (
      <div className="offline-banner" role="status" aria-live="polite" data-testid="sync-banner">
        <span className="ob-dot syncing" aria-hidden="true" />
        <span className="ob-text">正在同步 {entries.length} 项离线记录…</span>
      </div>
    );
  }

  if (failed > 0) {
    return (
      <div
        className="offline-banner ob-error"
        role="alert"
        aria-live="assertive"
        data-testid="sync-banner"
      >
        <span className="ob-dot" aria-hidden="true" />
        <span className="ob-text">
          {failed} 项同步失败{firstError ? `：${firstError}` : ''}
        </span>
        <button type="button" className="ob-retry" onClick={() => void flush()}>
          重试
        </button>
        <button
          type="button"
          className="ob-close"
          aria-label="关闭同步提示"
          title="关闭同步提示"
          onClick={() => clearResults()}
        >
          <CloseOutlined aria-hidden="true" />
        </button>
      </div>
    );
  }

  // 在线但队列里还有条目：自动重放没成功（例如刚恢复网络、或重试次数用尽），给一个手动出口
  if (entries.length > 0) {
    return (
      <div className="offline-banner" role="status" aria-live="polite" data-testid="sync-banner">
        <span className="ob-dot" aria-hidden="true" />
        <span className="ob-text">{entries.length} 项待同步</span>
        <button type="button" className="ob-retry" onClick={() => void flush()}>
          立即同步
        </button>
      </div>
    );
  }

  if (ok > 0) {
    return (
      <div
        className="offline-banner ob-ok"
        role="status"
        aria-live="polite"
        data-testid="sync-banner"
      >
        <span className="ob-dot" aria-hidden="true" />
        <span className="ob-text">{ok} 项离线记录已同步</span>
        <button
          type="button"
          className="ob-close"
          aria-label="关闭同步提示"
          title="关闭同步提示"
          onClick={() => clearResults()}
        >
          <CloseOutlined aria-hidden="true" />
        </button>
      </div>
    );
  }

  return null;
}
