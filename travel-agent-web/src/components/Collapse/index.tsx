import { useRef, useState, useEffect, useCallback, type ReactNode } from 'react';

interface Props {
  /** 是否展开 */
  open: boolean;
  children: ReactNode;
  /** 动画时长 ms，默认 280 */
  duration?: number;
  /** 折叠后是否完全移出 DOM（display:none），默认 false（只 overflow hidden） */
  unmountOnClose?: boolean;
  /** className */
  className?: string;
}

/**
 * 可折叠区域：展开/收起时高度从 0 → auto 的平滑动画。
 * 原理：展开时先设 auto 高度测出真实值，再用 px 做动画。
 */
export default function Collapse({
  open,
  children,
  duration = 280,
  unmountOnClose = false,
  className = '',
}: Props) {
  const [mounted, setMounted] = useState(open || !unmountOnClose);
  const [height, setHeight] = useState<string>(open ? 'auto' : '0px');
  const [overflow, setOverflow] = useState<string>(open ? 'visible' : 'hidden');
  const innerRef = useRef<HTMLDivElement>(null);
  const rafRef = useRef<number | null>(null);

  const measure = useCallback(() => {
    if (!innerRef.current) return 0;
    return innerRef.current.scrollHeight;
  }, []);

  // open 变化时启动动画
  useEffect(() => {
    if (open) {
      setMounted(true);
      setOverflow('hidden');

      // 先设为 0（如果是收起状态），下一帧再动画到目标
      if (height === '0px' || height === '') {
        setHeight('0px');
        rafRef.current = requestAnimationFrame(() => {
          const h = measure();
          setHeight(`${h}px`);
          // 动画结束后设为 auto，以适应后续内容变化
          const timer = setTimeout(() => {
            setHeight('auto');
            setOverflow('visible');
          }, duration);
          return () => clearTimeout(timer);
        });
      }
    } else {
      setOverflow('hidden');
      // 收起：先从 auto 变成 px，再动画到 0
      const current = measure();
      setHeight(`${current}px`);
      rafRef.current = requestAnimationFrame(() => {
        setHeight('0px');
        if (unmountOnClose) {
          const timer = setTimeout(() => setMounted(false), duration);
          return () => clearTimeout(timer);
        }
      });
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open]);

  useEffect(() => {
    return () => {
      if (rafRef.current) cancelAnimationFrame(rafRef.current);
    };
  }, []);

  if (!mounted) return null;

  return (
    <div
      className={`collapse-wrap ${className}`}
      style={{
        height,
        overflow,
        transition: `height ${duration}ms cubic-bezier(0.34, 1.3, 0.64, 1)`,
      }}
    >
      <div ref={innerRef} className="collapse-inner">
        {children}
      </div>
    </div>
  );
}
