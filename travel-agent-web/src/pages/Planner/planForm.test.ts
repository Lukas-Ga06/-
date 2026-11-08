import { describe, expect, it } from 'vitest';
import {
  clampPlanMessage,
  compilePlanPrompt,
  compilePlanSummary,
  emptyPlanForm,
  guidesTotalChars,
  isFormSubmittable,
  PLAN_MESSAGE_MAX,
  type PlanFormState,
} from './planForm';

function guidesForm(overrides: Partial<PlanFormState> = {}): PlanFormState {
  return {
    ...emptyPlanForm(),
    destination: '东京',
    days: 3,
    guides: [
      { title: '小红书｜东京五日', content: '第一天浅草寺，第二天涩谷。' },
      { title: '', content: '筑地市场吃海鲜。' },
    ],
    focus: ['减少步行', '筛选美食'],
    locked: '第三天下午已购买teamLab门票，请勿移动。',
    ...overrides,
  };
}

describe('planForm 编译器', () => {
  it('攻略模式：编译出需求要素、锁定项、优化目标与攻略正文', () => {
    const text = compilePlanPrompt(guidesForm());
    expect(text).toContain('目的地：东京');
    expect(text).toContain('出行时间：计划玩 3 天');
    expect(text).toContain('同行人员：独自出行（成人 1，儿童 0）');
    expect(text).toContain('预算：灵活');
    expect(text).toContain('不可修改的安排（务必原样保留）：第三天下午已购买teamLab门票');
    expect(text).toContain('整合攻略时重点优化：减少步行、筛选美食');
    expect(text).toContain('【小红书｜东京五日】');
    expect(text).toContain('筑地市场吃海鲜。');
    expect(text).toContain('以上共 2 篇参考攻略');
  });

  it('从零模式：不含攻略段落，收尾为从零规划指令', () => {
    const text = compilePlanPrompt(guidesForm({ mode: 'scratch', guides: [], focus: [], locked: '' }));
    expect(text).toContain('请从零规划一份完整行程。');
    expect(text).not.toContain('参考攻略');
    expect(text).not.toContain('重点优化');
  });

  it('具体日期范围优先展示，天数作为补充', () => {
    const text = compilePlanPrompt(guidesForm({ startDate: '2026-11-01', endDate: '2026-11-05' }));
    expect(text).toContain('出行时间：2026-11-01 至 2026-11-05，共 3 天');
  });

  it('预算启用时输出金额，未启用或非法值输出灵活', () => {
    expect(compilePlanPrompt(guidesForm({ budgetEnabled: true, budget: 3000 }))).toContain('预算：¥3000');
    expect(compilePlanPrompt(guidesForm({ budgetEnabled: true, budget: null }))).toContain('预算：灵活');
    expect(compilePlanPrompt(guidesForm({ budgetEnabled: false, budget: 3000 }))).toContain('预算：灵活');
  });

  it('空标题攻略自动编号占位', () => {
    const text = compilePlanPrompt(guidesForm({
      guides: [{ title: '', content: '正文A' }, { title: '', content: '正文B' }],
    }));
    expect(text).toContain('【参考攻略 1】');
    expect(text).toContain('【参考攻略 2】');
  });

  it('isFormSubmittable：缺目的地/时间/攻略正文都不可提交', () => {
    expect(isFormSubmittable(guidesForm())).toBe(true);
    expect(isFormSubmittable(guidesForm({ destination: ' ' }))).toBe(false);
    expect(isFormSubmittable(guidesForm({ days: null }))).toBe(false);
    expect(isFormSubmittable(guidesForm({ guides: [{ title: '', content: '   ' }] }))).toBe(false);
    expect(isFormSubmittable(guidesForm({ mode: 'scratch', guides: [] }))).toBe(true);
  });

  it('guidesTotalChars 统计全部正文长度', () => {
    expect(guidesTotalChars([{ title: '', content: '12345' }, { title: 'x', content: '1234' }])).toBe(9);
  });

  it('compilePlanSummary 不包含攻略正文', () => {
    const summary = compilePlanSummary(guidesForm());
    expect(summary).toContain('东京');
    expect(summary).toContain('计划玩 3 天');
    expect(summary).toContain('含 2 篇参考攻略');
    expect(summary).not.toContain('浅草寺');
  });

  it('clampPlanMessage 超长截断、正常长度原样返回', () => {
    expect(clampPlanMessage('短文本')).toBe('短文本');
    const long = '旅'.repeat(PLAN_MESSAGE_MAX + 100);
    const clamped = clampPlanMessage(long);
    expect(clamped.length).toBeLessThanOrEqual(PLAN_MESSAGE_MAX + 12);
    expect(clamped).toContain('已截断');
  });
});
