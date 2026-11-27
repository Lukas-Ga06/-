import { useSyncExternalStore } from 'react';

/** 当前是否在线：navigator.onLine 初值 + online/offline 事件跟踪 */
function readOnline(): boolean {
  if (typeof navigator === 'undefined') return true;
  // 个别浏览器里 navigator.onLine 恒为 true：读不到就当成在线，宁可不打扰用户
  return navigator.onLine !== false;
}

/**
 * 订阅浏览器在线状态。
 * 用 useSyncExternalStore 而不是「effect 里 setState 校准」：
 * 挂载前已经发生的事件不会丢，也不需要为一同步一次的状态多跑一轮渲染。
 */
function subscribe(onStoreChange: () => void): () => void {
  if (typeof window === 'undefined') return () => undefined;
  window.addEventListener('online', onStoreChange);
  window.addEventListener('offline', onStoreChange);
  return () => {
    window.removeEventListener('online', onStoreChange);
    window.removeEventListener('offline', onStoreChange);
  };
}

/** 服务端渲染没有 navigator：按在线处理 */
const serverSnapshot = () => true;

/**
 * 在线状态 hook：不引入新依赖，只监听浏览器原生事件。
 * 挂载时会读到最新状态——组件挂载前发生的事件同样不会漏
 *（例如从休眠恢复后直接以离线状态进入页面）。
 */
export function useOnlineStatus(): boolean {
  return useSyncExternalStore(subscribe, readOnline, serverSnapshot);
}
