import { useRef, useState, useCallback, useEffect, type ReactNode } from 'react';

interface Action {
  /** 按钮文字 */
  label: string;
  /** 背景色 */
  bg: string;
  /** 文字色，默认 #fff */
  color?: string;
  /** 点击回调 */
  onClick: () => void;
}

interface Props {
  children: ReactNode;
  /** 左滑显示的操作按钮（从右侧滑出） */
  rightActions?: Action[];
  /** 右滑显示的操作按钮（从左侧滑出） */
  leftActions?: Action[];
  /** 触发操作的阈值（px），默认 80 */
  threshold?: number;
  /** 最大可滑动距离（px），默认 160 */
  maxSlide?: number;
  /** 阻尼系数（0-1），越大力度越难滑，默认 0.5 */
  resistance?: number;
  /** 操作后自动关闭 */
  onActionClose?: boolean;
  /** className */
  className?: string;
}

/**
 * 滑动操作列表项：左滑/右滑显示操作按钮。
 * 支持触摸和鼠标拖拽，带阻尼回弹和自动吸附。
 */
export default function SwipeRow({
  children,
  rightActions = [],
  leftActions = [],
  threshold = 80,
  maxSlide = 160,
  resistance = 0.5,
  onActionClose = true,
  className = '',
}: Props) {
  const [offset, setOffset] = useState(0);
  const [animating, setAnimating] = useState(false);
  const startX = useRef(0);
  const startY = useRef(0);
  const startOffset = useRef(0);
  const dragging = useRef(false);
  const horizontalMoved = useRef(false);
  const containerRef = useRef<HTMLDivElement>(null);

  const maxRight = rightActions.length > 0 ? maxSlide : 0;
  const maxLeft = leftActions.length > 0 ? -maxSlide : 0;

  const handleStart = useCallback(
    (clientX: number, clientY: number) => {
      dragging.current = true;
      horizontalMoved.current = false;
      startX.current = clientX;
      startY.current = clientY;
      startOffset.current = offset;
      setAnimating(false);
    },
    [offset],
  );

  const handleMove = useCallback(
    (clientX: number, clientY: number) => {
      if (!dragging.current) return;
      const dx = clientX - startX.current;
      const dy = clientY - startY.current;

      // 判断方向：水平还是垂直
      if (!horizontalMoved.current) {
        if (Math.abs(dx) < 5 && Math.abs(dy) < 5) return;
        if (Math.abs(dy) > Math.abs(dx)) {
          dragging.current = false;
          return;
        }
        horizontalMoved.current = true;
      }

      let next = startOffset.current + dx;
      // 阻尼：超出边界时减速
      if (next > maxRight) {
        next = maxRight + (next - maxRight) * resistance;
      }
      if (next < maxLeft) {
        next = maxLeft + (next - maxLeft) * resistance;
      }
      setOffset(next);
    },
    [maxRight, maxLeft, resistance],
  );

  const handleEnd = useCallback(() => {
    if (!dragging.current) return;
    dragging.current = false;
    setAnimating(true);

    // 自动吸附到阈值或回 0
    if (offset > threshold && maxRight > 0) {
      setOffset(maxRight);
    } else if (offset < -threshold && maxLeft < 0) {
      setOffset(maxLeft);
    } else {
      setOffset(0);
    }
  }, [offset, threshold, maxRight, maxLeft]);

  // 触摸事件
  const onTouchStart = (e: React.TouchEvent) => {
    const t = e.touches[0];
    handleStart(t.clientX, t.clientY);
  };
  const onTouchMove = (e: React.TouchEvent) => {
    const t = e.touches[0];
    handleMove(t.clientX, t.clientY);
  };
  const onTouchEnd = () => handleEnd();

  // 鼠标事件
  const onMouseDown = (e: React.MouseEvent) => {
    handleStart(e.clientX, e.clientY);
  };
  useEffect(() => {
    if (!dragging.current) return;
    const onMove = (e: MouseEvent) => handleMove(e.clientX, e.clientY);
    const onUp = () => handleEnd();
    window.addEventListener('mousemove', onMove);
    window.addEventListener('mouseup', onUp);
    return () => {
      window.removeEventListener('mousemove', onMove);
      window.removeEventListener('mouseup', onUp);
    };
  });

  const handleAction = (action: Action) => {
    action.onClick();
    if (onActionClose) {
      setAnimating(true);
      setOffset(0);
    }
  };

  return (
    <div
      ref={containerRef}
      className={`swipe-row ${className}`}
      onTouchStart={onTouchStart}
      onTouchMove={onTouchMove}
      onTouchEnd={onTouchEnd}
      onMouseDown={onMouseDown}
    >
      {/* 左侧操作区 */}
      {leftActions.length > 0 && (
        <div className="swipe-actions left">
          {leftActions.map((a, i) => (
            <button
              key={i}
              className="swipe-action-btn"
              style={{ background: a.bg, color: a.color ?? '#fff' }}
              onClick={() => handleAction(a)}
            >
              {a.label}
            </button>
          ))}
        </div>
      )}
      {/* 右侧操作区 */}
      {rightActions.length > 0 && (
        <div className="swipe-actions right">
          {rightActions.map((a, i) => (
            <button
              key={i}
              className="swipe-action-btn"
              style={{ background: a.bg, color: a.color ?? '#fff' }}
              onClick={() => handleAction(a)}
            >
              {a.label}
            </button>
          ))}
        </div>
      )}
      <div
        className="swipe-content"
        style={{
          transform: `translateX(${offset}px)`,
          transition: animating ? 'transform 0.35s cubic-bezier(0.34, 1.4, 0.64, 1)' : 'none',
        }}
      >
        {children}
      </div>
    </div>
  );
}
