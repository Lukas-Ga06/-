import { useCallback, useEffect, useRef, useState } from 'react';
import './undo-bar.css';

interface Props {
  /**
   * 本次操作标识：父组件用 `key={token}` 重新挂载，倒计时自然从头开始。
   * 连续两次操作各有各的时间窗，不会互相续命。
   */
  token: number | string;
  /** 主文案：刚刚发生了什么（例如「已完成「外滩」」） */
  message: string;
  /** 次文案：接下来发生了什么（例如「已滚到下一项：豫园」） */
  detail?: string;
  actionLabel?: string;
  /** 撤销时间窗；超时后调用 onExpire 自动收起 */
  durationMs?: number;
  onAction: () => void;
  onExpire: () => void;
}

/**
 * 短时撤销条（§7.3 第 3 条 / §10.3）：状态一提交完就出现，倒计时结束自动收起。
 *
 * 为什么不用 message.success：antd 的成功提示没有可点击的撤销动作，
 * 而这个时间窗的全部价值就在「来得及反悔」；所以它是带进度的浮动条，
 * 并且 aria-live=polite —— 读屏用户同样能听到「已完成，可撤销」。
 */
export default function UndoBar({
  token,
  message,
  detail,
  actionLabel = '撤销',
  durationMs = 8000,
  onAction,
  onExpire,
}: Props) {
  const [msLeft, setMsLeft] = useState(durationMs);
  const timerRef = useRef<number | null>(null);
  // 回调身份每次渲染都会变，用 ref 镜像，避免计时器被无谓重启。
  // 只在 effect 里写 ref：渲染期间写 ref 会破坏「渲染是纯函数」的假设（react-hooks/refs）。
  const expireRef = useRef(onExpire);
  const actionRef = useRef(onAction);
  useEffect(() => {
    expireRef.current = onExpire;
    actionRef.current = onAction;
  });

  const stopTimer = useCallback(() => {
    if (timerRef.current != null) {
      window.clearInterval(timerRef.current);
      timerRef.current = null;
    }
  }, []);

  useEffect(() => {
    const started = Date.now();
    timerRef.current = window.setInterval(() => {
      const left = durationMs - (Date.now() - started);
      if (left <= 0) {
        stopTimer();
        setMsLeft(0);
        expireRef.current();
        return;
      }
      setMsLeft(left);
    }, 250);
    return stopTimer;
    // token 变化即重新计时；durationMs 一般恒定
  }, [token, durationMs, stopTimer]);

  // 点「撤销」或「关闭」都必须先停表：否则父组件若保留节点做异步收尾，
  // 超时回调还会再触发一次，出现「撤销成功后又被当成超时收起」的重复动作。
  const handleAction = () => {
    stopTimer();
    actionRef.current();
  };
  const handleDismiss = () => {
    stopTimer();
    expireRef.current();
  };

  const seconds = Math.max(1, Math.ceil(msLeft / 1000));
  const ratio = Math.min(1, Math.max(0, msLeft / durationMs));

  return (
    <div className="undo-bar" role="status" aria-live="polite" data-testid="undo-bar">
      <span className="ub-dot" aria-hidden="true" />
      <span className="ub-text">
        <b>{message}</b>
        {detail ? <span className="ub-detail">{detail}</span> : null}
      </span>
      <button type="button" className="ub-action" data-testid="undo-action" onClick={handleAction}>
        {actionLabel}（{seconds}s）
      </button>
      <button type="button" className="ub-close" aria-label="关闭撤销提示" onClick={handleDismiss}>
        ✕
      </button>
      <span className="ub-progress" aria-hidden="true">
        <span className="ub-progress-bar" style={{ width: `${ratio * 100}%` }} />
      </span>
    </div>
  );
}
