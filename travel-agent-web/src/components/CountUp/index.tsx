import { useEffect, useRef, useState } from 'react';

interface Props {
  /** 目标数值 */
  value: number;
  /** 动画时长（ms），默认 900 */
  duration?: number;
  /** 缓动函数，默认 easeOutExpo */
  easing?: (t: number) => number;
  /** 小数位数，默认 0 */
  decimals?: number;
  /** 前缀，如 ¥ */
  prefix?: string;
  /** 后缀，如 k */
  suffix?: string;
  /** 千分位分隔 */
  thousandSeparator?: boolean;
  /** 是否在视口可见时才开始动画，默认 true */
  playOnVisible?: boolean;
  /** className */
  className?: string;
  /** style */
  style?: React.CSSProperties;
}

const easeOutExpo = (t: number) => (t === 1 ? 1 : 1 - Math.pow(2, -10 * t));

function formatNumber(num: number, decimals: number, thousand: boolean): string {
  const fixed = num.toFixed(decimals);
  if (!thousand) return fixed;
  const [int, dec] = fixed.split('.');
  const withSep = int.replace(/\B(?=(\d{3})+(?!\d))/g, ',');
  return dec ? `${withSep}.${dec}` : withSep;
}

/**
 * 数字滚动动画：从 0 缓动到目标值。
 * 使用 requestAnimationFrame，在视口可见时才触发（可选）。
 */
export default function CountUp({
  value,
  duration = 900,
  easing = easeOutExpo,
  decimals = 0,
  prefix = '',
  suffix = '',
  thousandSeparator = false,
  playOnVisible = true,
  className,
  style,
}: Props) {
  const [display, setDisplay] = useState(0);
  const ref = useRef<HTMLSpanElement>(null);
  const startedRef = useRef(false);
  const rafRef = useRef<number | null>(null);

  useEffect(() => {
    if (playOnVisible && ref.current) {
      const el = ref.current;
      const observer = new IntersectionObserver(
        (entries) => {
          if (entries[0]?.isIntersecting && !startedRef.current) {
            startedRef.current = true;
            animate();
          }
        },
        { threshold: 0.3 },
      );
      observer.observe(el);
      return () => observer.disconnect();
    } else {
      animate();
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // value 变化时重新动画
  useEffect(() => {
    if (!playOnVisible || startedRef.current) {
      animate();
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [value]);

  function animate() {
    const start = performance.now();
    const from = 0;
    const to = value;

    function tick(now: number) {
      const elapsed = now - start;
      const t = Math.min(elapsed / duration, 1);
      const eased = easing(t);
      setDisplay(from + (to - from) * eased);
      if (t < 1) {
        rafRef.current = requestAnimationFrame(tick);
      }
    }
    rafRef.current = requestAnimationFrame(tick);
  }

  useEffect(() => {
    return () => {
      if (rafRef.current) cancelAnimationFrame(rafRef.current);
    };
  }, []);

  return (
    <span ref={ref} className={className} style={style}>
      {prefix}
      {formatNumber(display, decimals, thousandSeparator)}
      {suffix}
    </span>
  );
}
