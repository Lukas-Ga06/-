/**
 * 结构化规划表单 → Agent 需求文本 的纯编译逻辑（参考 ziyutravel.cn 的双模式规划表单）。
 * 只做字符串拼装，不发请求、不依赖组件，方便单测。
 */

export type PlanMode = 'guides' | 'scratch';
export type CompanionType = 'alone' | 'partner' | 'friends' | 'parents' | 'kids' | 'other';
export type Pace = 'relaxed' | 'balanced' | 'packed';

export interface GuideInput {
  /** 标题或来源（选填），如「小红书｜伦敦五日景点攻略」 */
  title: string;
  content: string;
}

export interface PlanFormState {
  mode: PlanMode;
  destination: string;
  /** 出行时间两种形态：直接给天数，或给具体日期范围 */
  days: number | null;
  startDate: string | null;
  endDate: string | null;
  companion: CompanionType;
  adults: number;
  kids: number;
  budgetEnabled: boolean;
  budget: number | null;
  pace: Pace;
  interests: string[];
  notes: string;
  /** 攻略整合模式专用 */
  guides: GuideInput[];
  focus: string[];
  /** 哪些内容不能被修改（已购门票、必须去的景点等） */
  locked: string;
}

export const COMPANION_LABEL: Record<CompanionType, string> = {
  alone: '独自出行',
  partner: '和伴侣出行',
  friends: '和朋友出行',
  parents: '带父母出行',
  kids: '带儿童出行',
  other: '其他组合',
};

export const PACE_LABEL: Record<Pace, string> = {
  relaxed: '轻松悠闲（行程留白更多）',
  balanced: '张弛有度（重点体验与休息兼顾）',
  packed: '尽量多玩（接受更高行程密度）',
};

export const INTEREST_OPTIONS = [
  '经典地标', '历史文化', '当地美食', '自然风景',
  '拍照打卡', '购物', '亲子体验', '小众街区',
] as const;

export const FOCUS_OPTIONS = [
  '减少跨区移动', '减少步行', '降低行程密度', '控制预算',
  '筛选美食', '适合父母或儿童', '调整景点顺序', '保留景点，只优化路线',
] as const;

/** 单篇攻略正文上限（字符） */
export const GUIDE_MAX_CHARS = 6000;
/** 全部攻略合计上限（字符） */
export const GUIDES_MAX_TOTAL = 15000;
/** POST /api/agent/stream 校验的 message 上限，留 2000 余量给编译出的需求框架 */
export const PLAN_MESSAGE_MAX = 18000;

export function emptyPlanForm(): PlanFormState {
  return {
    mode: 'guides',
    destination: '',
    days: null,
    startDate: null,
    endDate: null,
    companion: 'alone',
    adults: 1,
    kids: 0,
    budgetEnabled: false,
    budget: null,
    pace: 'balanced',
    interests: [],
    notes: '',
    guides: [{ title: '', content: '' }],
    focus: [],
    locked: '',
  };
}

export function guidesTotalChars(guides: GuideInput[]): number {
  return guides.reduce((sum, g) => sum + g.content.length, 0);
}

export function isFormSubmittable(form: PlanFormState): boolean {
  if (!form.destination.trim()) return false;
  const hasDays = form.days != null && form.days > 0;
  const hasDates = Boolean(form.startDate && form.endDate);
  if (!hasDays && !hasDates) return false;
  if (form.mode === 'guides') {
    const usable = form.guides.filter((g) => g.content.trim().length > 0);
    return usable.length > 0;
  }
  return true;
}

function describeTime(form: PlanFormState): string {
  if (form.startDate && form.endDate) {
    const days = form.days && form.days > 0 ? form.days : null;
    return `${form.startDate} 至 ${form.endDate}${days ? `，共 ${days} 天` : ''}`;
  }
  if (form.days && form.days > 0) {
    return `计划玩 ${form.days} 天`;
  }
  return '时间待定';
}

function describeCompanions(form: PlanFormState): string {
  const base = COMPANION_LABEL[form.companion];
  return `${base}（成人 ${form.adults || 1}，儿童 ${form.kids || 0}）`;
}

/**
 * 把表单编译成一段完整的中文需求文本交给 Agent。
 * 输出结构固定：需求要素 → 锁定项 → 优化目标 → 攻略正文 → 整合指令。
 */
export function compilePlanPrompt(form: PlanFormState): string {
  const lines: string[] = [];
  lines.push('请为我规划一次旅行。以下是结构化表单提交的完整需求，信息以本条消息为准，无需再次向我确认这些字段。');
  lines.push(`目的地：${form.destination.trim()}`);
  lines.push(`出行时间：${describeTime(form)}`);
  lines.push(`同行人员：${describeCompanions(form)}`);
  lines.push(
    form.budgetEnabled && form.budget != null && form.budget > 0
      ? `预算：¥${form.budget}`
      : '预算：灵活',
  );
  lines.push(`旅行节奏：${PACE_LABEL[form.pace]}`);
  if (form.interests.length > 0) {
    lines.push(`偏好体验：${form.interests.join('、')}`);
  }
  if (form.notes.trim()) {
    lines.push(`特别要求：${form.notes.trim()}`);
  }
  if (form.mode === 'guides') {
    if (form.locked.trim()) {
      lines.push(`不可修改的安排（务必原样保留）：${form.locked.trim()}`);
    }
    if (form.focus.length > 0) {
      lines.push(`整合攻略时重点优化：${form.focus.join('、')}`);
    }
    const usable = form.guides.filter((g) => g.content.trim().length > 0);
    usable.forEach((g, i) => {
      const title = g.title.trim() || `参考攻略 ${i + 1}`;
      lines.push(`【${title}】`);
      lines.push(g.content.trim());
    });
    lines.push(
      `以上共 ${usable.length} 篇参考攻略：请把攻略内容与我的需求合并、筛选并重新安排成一份完整可执行的行程，` +
        '去掉重复项，保留攻略里独特且可靠的推荐，并按上面的节奏与优化目标排布。',
    );
  } else {
    if (form.locked.trim()) {
      lines.push(`不可修改的安排（务必原样保留）：${form.locked.trim()}`);
    }
    lines.push('请从零规划一份完整行程。');
  }
  return lines.join('\n');
}

/** 聊天记录里展示用的短摘要（攻略正文不进聊天气泡） */
export function compilePlanSummary(form: PlanFormState): string {
  const parts = [form.destination.trim() || '目的地待定'];
  parts.push(describeTime(form));
  parts.push(COMPANION_LABEL[form.companion]);
  if (form.mode === 'guides') {
    const usable = form.guides.filter((g) => g.content.trim().length > 0);
    parts.push(`含 ${usable.length} 篇参考攻略`);
  }
  return `结构化需求提交：${parts.join(' · ')}`;
}

/**
 * 兜底裁剪：理论上表单约束（3 篇 × 6000 + 需求框架）不会超过上限，
 * 但用户可以绕过 UI 直接改状态，这里保证最终 message 不被服务端 2 万字符校验拒绝。
 */
export function clampPlanMessage(message: string): string {
  if (message.length <= PLAN_MESSAGE_MAX) {
    return message;
  }
  return message.slice(0, PLAN_MESSAGE_MAX) + '\n（内容过长，已截断）';
}
