/**
 * Agent 运行状态的「徽标投影」：`RunState` 七态 → 徽标文案 / 色档 / 补充说明。
 *
 * 与 `lib/runState.ts` 的分工：那边的 `toRunState()` 负责「来源取值 → 七态」的唯一判断
 * （卡 #4 已验证）；本文件只做渲染层投影（七态 → 既有 `.run-badge` 色档类名、
 * ABANDONED / 未登记状态的补充文案），不重新判断后端取值。
 * Workbench 与 AgentRunsDrawer 共用它，避免两处各写一份 switch 再次漂移。
 */

import { RUN_STATE_META, toRunState, type RunStateOrUnknown, type RunStateTone } from './runState';

/**
 * ABANDONED 的完整用户文案：要让人看出「不是自己取消的，是系统没跑完」。
 * 后端把它与 FAILED / CANCELLED 分开（`AgentRun.java:51`），这里也不许缩写成一个「失败」。
 */
export const ABANDONED_RESULT_TEXT = '结果未知（进程重启或心跳超时）';

/** 徽标色档沿用既有 `.run-badge` 类名（danger→fail、info→run 是历史命名） */
const BADGE_TONE_CLASS: Record<RunStateTone, string> = {
  ok: 'ok',
  warn: 'warn',
  danger: 'fail',
  muted: 'muted',
  info: 'run',
};

export interface AgentRunBadgeView {
  /** 七态之一，或映射层的 unknown 兜底（未知取值不能当成功） */
  state: RunStateOrUnknown;
  /** 徽标短文案 */
  label: string;
  /** 七态的标准下一步提示 */
  hint: string;
  /** `.run-badge` 的色档类名 */
  toneClass: string;
  /** 需要一句补充说明时给出整句（ABANDONED 结果未知 / 未登记状态），否则 null */
  note: string | null;
  /** 来源原始取值，便于排查未知状态 */
  raw: string;
}

export function agentRunBadge(status: unknown): AgentRunBadgeView {
  const mapping = toRunState('agent-run', status);
  const meta = RUN_STATE_META[mapping.state];
  const abandoned = mapping.raw === 'ABANDONED';
  return {
    state: mapping.state,
    label: abandoned ? '结果未知' : meta.label,
    hint: meta.hint,
    toneClass: BADGE_TONE_CLASS[meta.tone],
    note: abandoned
      ? ABANDONED_RESULT_TEXT
      : mapping.state === 'unknown'
        ? `后端返回了未登记的运行状态「${mapping.raw}」`
        : null,
    raw: mapping.raw,
  };
}
