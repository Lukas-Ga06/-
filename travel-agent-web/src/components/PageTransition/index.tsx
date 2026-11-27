import { useEffect, useState, type ReactNode } from 'react';
import { useLocation } from 'react-router-dom';

type Direction = 'forward' | 'back' | 'none';

interface Props {
  children: ReactNode;
  /** 过渡类型 */
  variant?: 'fade' | 'slide' | 'scale';
  /** 动画时长 ms */
  duration?: number;
}

/**
 * 页面过渡动画：根据路由变化触发 key-based 过渡。
 * 用 useLocation.pathname 作为 key，路由改变时重新挂载子树，
 * 通过 CSS class 切换实现淡入/滑动/缩放。
 */
export default function PageTransition({ children, variant = 'fade', duration = 300 }: Props) {
  const location = useLocation();
  const [direction, setDirection] = useState<Direction>('none');
  const [currentPath, setCurrentPath] = useState(location.pathname);

  // 路由变化：在渲染期收敛到最新路径并标记方向（值变化时调整 state）。
  // currentPath 是子树 key，路径一变就重新挂载触发 CSS 动画，无需 effect 中转。
  if (location.pathname !== currentPath) {
    setDirection('forward');
    setCurrentPath(location.pathname);
  }

  useEffect(() => {
    if (direction !== 'none') {
      const timer = setTimeout(() => setDirection('none'), duration);
      return () => clearTimeout(timer);
    }
  }, [direction, duration]);

  const cls = `page-transition page-transition-${variant} ${direction === 'forward' ? 'entering' : 'entered'}`;

  return (
    <div key={currentPath} className={cls} style={{ animationDuration: `${duration}ms` }}>
      {children}
    </div>
  );
}
