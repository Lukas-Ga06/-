import { useEffect } from 'react';
import { useAgentStore } from '../store/agentStore';

/**
 * 页面级会话作用域（体验方案阶段 C · §8.6）。
 *
 * 为什么在页面而不是 ChatPanel 里切作用域：规划器是先「提交表单」再渲染 ChatPanel 的，
 * 如果由面板挂载时切作用域，会把页面刚发起的在途流当成旧流取消掉（确认弹窗永远不出现）。
 * 页面在挂载时就切好，发送与渲染顺序都不会影响它。
 */
export function useAgentScope(scope: string): void {
  const setScope = useAgentStore((s) => s.setScope);
  useEffect(() => {
    setScope?.(scope);
  }, [scope, setScope]);
}
