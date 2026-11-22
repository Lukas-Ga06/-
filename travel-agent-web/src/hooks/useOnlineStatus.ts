import { useEffect, useState } from 'react';

/** 当前是否在线：navigator.onLine 初值 + online/offline 事件跟踪 */
function readOnline(): boolean {
  if (typeof navigator === 'undefined') return true;
  // 个别浏览器里 navigator.onLine 恒为 true：读不到就当成在线，宁可不打扰用户
  return navigator.onLine !== false;
}

/**
 * 在线状态 hook：不引入新依赖，只监听浏览器原生事件。
 * 挂载时再读一次 navigator.onLine——组件挂载前发生的事件是收不到的
 *（例如从休眠恢复后直接以离线状态进入页面）。
 */
export function useOnlineStatus(): boolean {
  const [online, setOnline] = useState<boolean>(readOnline);

  useEffect(() => {
    if (typeof window === 'undefined') return;
    const goOnline = () => setOnline(true);
    const goOffline = () => setOnline(false);
    window.addEventListener('online', goOnline);
    window.addEventListener('offline', goOffline);
    // 事件可能已经错过：挂载时校准一次真实状态
    setOnline(readOnline());
    return () => {
      window.removeEventListener('online', goOnline);
      window.removeEventListener('offline', goOffline);
    };
  }, []);

  return online;
}
