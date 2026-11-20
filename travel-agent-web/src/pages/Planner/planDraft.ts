/**
 * 规划三步流程的草稿与步骤纯逻辑（无 React / DOM 依赖）。
 *
 * 单独成文件的原因：
 * - 草稿必须容错（坏 JSON、字段缺失、隐私模式禁用 localStorage），这些分支要能穷举单测；
 * - 步骤门禁与文案和 UI 解耦：页面只负责「显示哪一句、停在哪个步骤」，判定本身可测。
 */
import {
  COMPANION_LABEL,
  PACE_LABEL,
  type CompanionType,
  type GuideInput,
  type Pace,
  type PlanFormState,
} from './planForm';

export type PlannerStep = 1 | 2 | 3;

/** 三步顺序只写一份：进度条与前进/回退都读它，避免顺序散落在多处 */
export const PLANNER_STEPS: readonly PlannerStep[] = [1, 2, 3];

/**
 * 草稿 key 带版本号：草稿结构或表单字段变化时，旧草稿自然失效，
 * 而不是把缺字段的脏数据灌进新表单。
 */
export const DRAFT_STORAGE_KEY = 'travel:planner-draft:v1';
export const DRAFT_VERSION = 1;

/** 自动保存防抖窗口：攻略正文可达一万五千字，不必每敲一个键就序列化一次 */
export const DRAFT_SAVE_DELAY_MS = 400;

export interface PlanDraft {
  version: number;
  form: PlanFormState;
  step: PlannerStep;
  /** 最后编辑时间（毫秒时间戳），「恢复上次未完成规划」提示里展示 */
  updatedAt: number;
}

/** 只依赖这三个方法，测试可注入内存实现；浏览器里默认用 localStorage */
export interface DraftStorage {
  getItem(key: string): string | null;
  setItem(key: string, value: string): void;
  removeItem(key: string): void;
}

const NEXT_STEP: Record<PlannerStep, PlannerStep> = { 1: 2, 2: 3, 3: 3 };
const PREV_STEP: Record<PlannerStep, PlannerStep> = { 1: 1, 2: 1, 3: 2 };

function resolveStorage(storage?: DraftStorage | null): DraftStorage | null {
  if (storage) return storage;
  try {
    // 隐私模式/被策略禁用时，读 localStorage 本身就会抛异常 → 当作「没有存储」
    return typeof globalThis.localStorage === 'undefined' ? null : globalThis.localStorage;
  } catch {
    return null;
  }
}

export function isPlannerStep(value: unknown): value is PlannerStep {
  return value === 1 || value === 2 || value === 3;
}

export function nextStep(step: PlannerStep): PlannerStep {
  return NEXT_STEP[step];
}

export function prevStep(step: PlannerStep): PlannerStep {
  return PREV_STEP[step];
}

/** 出行时间：天数 > 0 或起止日期都给了，任一成立即可 */
export function hasPlanTime(form: PlanFormState): boolean {
  const hasDays = form.days != null && form.days > 0;
  const hasDates = Boolean(form.startDate && form.endDate);
  return hasDays || hasDates;
}

export function hasGuideContent(form: PlanFormState): boolean {
  return form.guides.some((guide) => guide.content.trim().length > 0);
}

/**
 * 当前步骤「继续」前的门禁：null 表示可以进入下一步，否则返回要展示给用户的文案。
 * 第 1 步是必填项；第 2 步只有攻略模式要求至少一篇正文（从零规划可全部留空）。
 */
export function stepIssue(step: PlannerStep, form: PlanFormState): string | null {
  if (step === 1) {
    if (!form.destination.trim()) return '先填写目的地';
    if (!hasPlanTime(form)) return '请填写出行时间';
    if (!(form.adults >= 1)) return '请填写同行人数';
    return null;
  }
  if (step === 2 && form.mode === 'guides' && !hasGuideContent(form)) {
    return '攻略模式至少需要一篇攻略正文，或切换为「我还没有攻略」';
  }
  return null;
}

/**
 * 表单里是否有值得存成草稿的用户输入。
 * 空白表单（默认值）不算：否则每次打开规划页都会留下一条空草稿，
 * 下次进来弹出「恢复上次未完成规划」却什么都没有。
 */
export function hasRequirementInput(form: PlanFormState): boolean {
  return Boolean(
    form.destination.trim() ||
    form.notes.trim() ||
    form.locked.trim() ||
    form.focus.length > 0 ||
    form.interests.length > 0 ||
    form.budgetEnabled ||
    (form.days != null && form.days > 0) ||
    form.startDate ||
    form.endDate ||
    form.guides.some((guide) => guide.title.trim() || guide.content.trim()),
  );
}

/** 草稿是否值得提示恢复：进过第 2 步，或第 1 步已经填了实际内容 */
export function isDraftUsable(draft: PlanDraft | null): draft is PlanDraft {
  return draft !== null && (draft.step > 1 || hasRequirementInput(draft.form));
}

export function createDraft(
  form: PlanFormState,
  step: PlannerStep,
  updatedAt: number = Date.now(),
): PlanDraft {
  return { version: DRAFT_VERSION, form, step, updatedAt };
}

/**
 * 写入草稿。返回是否成功：配额已满/隐私模式下写不进去，
 * 页面据此决定要不要提示「本次草稿没保存成功」，而不是静默失败。
 */
export function saveDraft(draft: PlanDraft, storage?: DraftStorage | null): boolean {
  const store = resolveStorage(storage);
  if (!store) return false;
  try {
    store.setItem(DRAFT_STORAGE_KEY, JSON.stringify(draft));
    return true;
  } catch {
    return false;
  }
}

/**
 * 读取草稿。任何异常情况（没有存储、无效 JSON、版本不符、字段缺失或类型不对）
 * 一律返回 null：宁可不恢复，也不能把半截数据填进表单让用户误以为是自己的输入。
 */
export function readDraft(storage?: DraftStorage | null): PlanDraft | null {
  const store = resolveStorage(storage);
  if (!store) return null;

  let raw: string | null = null;
  try {
    raw = store.getItem(DRAFT_STORAGE_KEY);
  } catch {
    return null;
  }
  if (!raw) return null;

  let parsed: unknown;
  try {
    parsed = JSON.parse(raw);
  } catch {
    return null;
  }

  if (!isRecord(parsed)) return null;
  if (parsed.version !== DRAFT_VERSION) return null;
  if (!isPlannerStep(parsed.step)) return null;
  if (typeof parsed.updatedAt !== 'number' || !Number.isFinite(parsed.updatedAt)) return null;
  if (!isPlanFormState(parsed.form)) return null;

  return {
    version: DRAFT_VERSION,
    form: parsed.form,
    step: parsed.step,
    updatedAt: parsed.updatedAt,
  };
}

/** 清除草稿；没有存储或删除失败都不抛异常（调用方不需要区分） */
export function clearDraft(storage?: DraftStorage | null): void {
  const store = resolveStorage(storage);
  if (!store) return;
  try {
    store.removeItem(DRAFT_STORAGE_KEY);
  } catch {
    // 删除失败只会留下一条旧草稿，不影响当前页面继续使用
  }
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null;
}

function isStringOrNull(value: unknown): value is string | null {
  return value === null || typeof value === 'string';
}

function isFiniteNumberOrNull(value: unknown): value is number | null {
  return value === null || (typeof value === 'number' && Number.isFinite(value));
}

function isFiniteNumber(value: unknown): value is number {
  return typeof value === 'number' && Number.isFinite(value);
}

function isStringArray(value: unknown): value is string[] {
  return Array.isArray(value) && value.every((item) => typeof item === 'string');
}

function isGuideArray(value: unknown): value is GuideInput[] {
  return (
    Array.isArray(value) &&
    value.every(
      (guide) =>
        isRecord(guide) && typeof guide.title === 'string' && typeof guide.content === 'string',
    )
  );
}

/** 用 hasOwnProperty 而不是 in：`'toString' in COMPANION_LABEL` 会命中原型链 */
function isCompanion(value: unknown): value is CompanionType {
  return typeof value === 'string' && Object.prototype.hasOwnProperty.call(COMPANION_LABEL, value);
}

function isPace(value: unknown): value is Pace {
  return typeof value === 'string' && Object.prototype.hasOwnProperty.call(PACE_LABEL, value);
}

/** 严格校验表单结构：任何字段缺失/类型不符都判为坏草稿 */
export function isPlanFormState(value: unknown): value is PlanFormState {
  if (!isRecord(value)) return false;
  if (value.mode !== 'guides' && value.mode !== 'scratch') return false;
  if (typeof value.destination !== 'string') return false;
  if (!isFiniteNumberOrNull(value.days)) return false;
  if (!isStringOrNull(value.startDate) || !isStringOrNull(value.endDate)) return false;
  if (!isCompanion(value.companion)) return false;
  if (!isFiniteNumber(value.adults) || !isFiniteNumber(value.kids)) return false;
  if (typeof value.budgetEnabled !== 'boolean') return false;
  if (!isFiniteNumberOrNull(value.budget)) return false;
  if (!isPace(value.pace)) return false;
  if (!isStringArray(value.interests)) return false;
  if (typeof value.notes !== 'string') return false;
  if (!isGuideArray(value.guides)) return false;
  if (!isStringArray(value.focus)) return false;
  if (typeof value.locked !== 'string') return false;
  return true;
}
