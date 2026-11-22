/**
 * 登出 / 换账号时的**统一客户端清理入口**。
 *
 * 为什么需要它：登录态的权威在服务端 Cookie，但界面状态活在多个 zustand store
 * 与 localStorage 里（对话与待确认方案、行程列表与详情、跨页选中上下文、
 * 规划草稿、离线待同步队列）。登出只清 authStore 的话，同一浏览器换账号后
 * 下一个人会看到上一个人的聊天记录、行程标题，甚至被问「恢复上次未完成规划？」。
 *
 * 约束：
 * - 只清本地状态，不发请求（登出接口由 authStore 自己负责）；
 * - 每个清理都单独 try/catch：任一存储不可用（隐私模式、配额满）都不能阻断登出；
 * - 接口 401 的强制登出也走这里（见 api/client.ts 的拦截器 → authStore.logout）。
 */
import { useAgentStore } from './agentStore';
import { useTripStore } from './tripStore';
import { useUIStore } from './uiStore';
import { useStatusQueueStore } from './statusQueueStore';
import { clearDraft } from '../pages/Planner/planDraft';

export function clearClientSession(): void {
  // 顺序无关，但都必须在登出返回前完成：登出后立刻登录时没有「稍后清理」的窗口
  safely(() => useAgentStore.getState().clearSession());
  safely(() => useTripStore.getState().reset());
  safely(() => useUIStore.getState().reset());
  // 规划草稿与离线队列是 localStorage 里的全局 key（不区分用户），必须显式删，
  // 否则下一个人会被提示恢复上一个人的草稿，离线队列还会以新用户身份重放
  safely(() => clearDraft());
  safely(() => useStatusQueueStore.getState().clear());
}

function safely(run: () => void): void {
  try {
    run();
  } catch {
    // 隐私模式下 storage 抛错是已知边界：清理失败也不能让登出卡住
  }
}
