import { useEffect, useRef, useState } from 'react';

/**
 * 观察元素是否进入视口。
 * 返回 [ref, isVisible] —— 把 ref 挂到目标元素上即可。
 *
 * @example
 * const [ref, visible] = useIntersection({ threshold: 0.15 });
 * <div ref={ref} className={visible ? 'in-view' : ''}>...</div>
 */
export function useIntersection<T extends HTMLElement = HTMLDivElement>(
  options?: IntersectionObserverInit & { once?: boolean },
) {
  const [visible, setVisible] = useState(false);
  const ref = useRef<T>(null);
  const once = options?.once ?? false;

  useEffect(() => {
    const el = ref.current;
    if (!el || typeof IntersectionObserver === 'undefined') {
      setVisible(true);
      return;
    }

    const observer = new IntersectionObserver(
      (entries) => {
        const entry = entries[0];
        if (entry?.isIntersecting) {
          setVisible(true);
          if (once) observer.disconnect();
        } else if (!once) {
          setVisible(false);
        }
      },
      {
        threshold: options?.threshold ?? 0.15,
        root: options?.root ?? null,
        rootMargin: options?.rootMargin ?? '0px',
      },
    );

    observer.observe(el);
    return () => observer.disconnect();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  return [ref, visible] as const;
}
