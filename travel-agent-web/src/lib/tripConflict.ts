import { getErrorStatus } from '../api/client';

/**
 * 阶段 D：行程冲突提示。
 *
 * 「此行程已在其他位置更新」有两个来源，前端用同一句文案、同一套处理：
 * 1. **服务端拒绝（409）**：Trip 有 `@Version` 乐观锁，两个标签页/设备同时提交时，
 *    后提交的一方拿到 409（见后端 `GlobalExceptionHandler` 与 `OptimisticLockConflictTest`）。
 *    此时本地这份数据已经过期，继续重试只会重复失败，必须刷新后由用户重新决策。
 * 2. **主动发现**：回到页面（tab 重新可见）时对比 `Trip.version`，
 *    版本变了说明别处改过，静默刷新并提示，避免用户对着旧时间轴继续操作。
 *
 * 只做提示与刷新，不替用户合并：冲突的语义是「谁后写谁赢」，前端无权猜测用户意图。
 */
export const TRIP_CONFLICT_MESSAGE = '此行程已在其他位置更新';

/** 409 才算冲突：400/404/422 是普通的业务拒绝，文案与处理都不同 */
export function isTripConflict(error: unknown): boolean {
  return getErrorStatus(error) === 409;
}

/**
 * 版本是否变了。任一版本缺失（旧后端不返回 version）时一律返回 false：
 * 宁可不提示，也不要在没有依据时打断用户。
 */
export function hasRemoteUpdate(
  previous: number | null | undefined,
  incoming: number | null | undefined,
): boolean {
  if (typeof previous !== 'number' || typeof incoming !== 'number') return false;
  return previous !== incoming;
}

/** 冲突后的统一提示：说明发生了什么 + 已经做了什么（刷新），不留悬空状态 */
export function conflictNotice(done = '已为你刷新到最新'): string {
  return `${TRIP_CONFLICT_MESSAGE}，${done}`;
}
