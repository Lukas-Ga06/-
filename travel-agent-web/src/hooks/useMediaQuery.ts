import { useEffect, useState } from 'react';

/**
 * 媒体查询订阅：布局需要按视口切换「结构」而不只是样式时使用
 * （例如行程页在移动端是分段视图、桌面端是双栏）。
 *
 * <p>无 matchMedia 的环境（SSR / 部分测试环境）退化为 false，
 * 即「按桌面结构渲染」——不会因为拿不到媒体查询就渲染出移动端布局。
 */
export function useMediaQuery(query: string): boolean {
  const [matches, setMatches] = useState(() => {
    if (typeof window === 'undefined' || typeof window.matchMedia !== 'function') return false;
    return window.matchMedia(query).matches;
  });

  useEffect(() => {
    if (typeof window === 'undefined' || typeof window.matchMedia !== 'function') return;
    const mql = window.matchMedia(query);
    const onChange = () => setMatches(mql.matches);
    onChange();
    // 老 Safari 只有 addListener：两种都挂，拿得到哪个用哪个
    if (typeof mql.addEventListener === 'function') {
      mql.addEventListener('change', onChange);
      return () => mql.removeEventListener('change', onChange);
    }
    mql.addListener(onChange);
    return () => mql.removeListener(onChange);
  }, [query]);

  return matches;
}

/** 与 global.css 的移动断点保持一致（720px） */
export const useIsMobile = (): boolean => useMediaQuery('(max-width: 720px)');
