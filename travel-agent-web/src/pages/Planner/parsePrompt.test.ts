import { describe, expect, it } from 'vitest';
import {
  applyParsedToForm,
  describeParsed,
  lowConfidenceHints,
  parsePlanPrompt,
} from './parsePrompt';
import { emptyPlanForm } from './planForm';

describe('parsePlanPrompt · 一句话需求解析（P1-02）', () => {
  it('「周末上海两日游，2人，预算3000元」把四个字段都回填', () => {
    const parsed = parsePlanPrompt('帮我规划周末上海两日游，2人，预算3000元');

    expect(parsed.destination).toBe('上海');
    expect(parsed.days).toBe(2);
    expect(parsed.adults).toBe(2);
    expect(parsed.budget).toBe(3000);
    expect(parsed.matched).toBe(true);
    // 四个字段都是直接读出来的，不需要用户再确认
    expect(parsed.confidence.destination).toBe('high');
    expect(parsed.confidence.days).toBe('high');
    expect(parsed.confidence.people).toBe('high');
    expect(parsed.confidence.budget).toBe('high');
    // 全部被吸收后不该再往「特别要求」里塞一遍原话
    expect(parsed.leftovers).toBe('');
  });

  it('「去杭州玩三天，预算8000」识别目的地与天数', () => {
    const parsed = parsePlanPrompt('去杭州玩三天，预算8000');

    expect(parsed.destination).toBe('杭州');
    expect(parsed.days).toBe(3);
    expect(parsed.budget).toBe(8000);
  });

  it('日期范围与天数一致时不报冲突', () => {
    const parsed = parsePlanPrompt('上海三日游，11月21日-11月23日');

    expect(parsed.days).toBe(3);
    expect(parsed.startDate).toBe('2026-11-21');
    expect(parsed.endDate).toBe('2026-11-23');
    expect(parsed.conflicts).toEqual([]);
  });

  it('两天与三天日期范围冲突时记录下来，不静默覆盖', () => {
    const parsed = parsePlanPrompt('上海两日游，11月21日-11月23日');

    expect(parsed.days).toBe(2);
    expect(parsed.conflicts).toHaveLength(1);
    expect(parsed.conflicts[0]).toContain('3 天');
    expect(parsed.conflicts[0]).toContain('请确认');
  });

  it('「北京亲子游，玩三天，预算6000」：只说了亲子就按 1 小待确认，不编造大人数', () => {
    const parsed = parsePlanPrompt('北京亲子游，玩三天，预算6000元');

    expect(parsed.destination).toBe('北京');
    expect(parsed.days).toBe(3);
    expect(parsed.budget).toBe(6000);
    expect(parsed.kids).toBe(1);
    expect(parsed.confidence.people).toBe('low');
    // 城市名靠兜底命中，必须提示用户确认
    expect(parsed.confidence.destination).toBe('low');
  });

  it('「3大1小去成都玩四天」拆出大人与孩子', () => {
    const parsed = parsePlanPrompt('3大1小去成都玩四天');

    expect(parsed.destination).toBe('成都');
    expect(parsed.days).toBe(4);
    expect(parsed.adults).toBe(3);
    expect(parsed.kids).toBe(1);
    expect(parsed.confidence.people).toBe('high');
  });

  it('「两个人去厦门，人均800」按人数折算总额，并标低置信', () => {
    const parsed = parsePlanPrompt('两个人去厦门，人均800');

    expect(parsed.destination).toBe('厦门');
    expect(parsed.adults).toBe(2);
    expect(parsed.budget).toBe(1600);
    expect(parsed.confidence.budget).toBe('low');
  });

  it('「独自去西安，预算1.5万」识别单人出行与万元单位', () => {
    const parsed = parsePlanPrompt('独自去西安，预算1.5万');

    expect(parsed.destination).toBe('西安');
    expect(parsed.adults).toBe(1);
    expect(parsed.budget).toBe(15000);
  });

  it('「周末去苏州」天数按周末粗估 2 天并标低置信', () => {
    const parsed = parsePlanPrompt('周末去苏州');

    expect(parsed.destination).toBe('苏州');
    expect(parsed.days).toBe(2);
    expect(parsed.confidence.days).toBe('low');
    expect(lowConfidenceHints(parsed)).toContain('天数按「周末」粗估为 2 天，请确认');
  });

  it('识别兴趣偏好，并把这些词从特别要求里去掉', () => {
    const parsed = parsePlanPrompt('上海三天，喜欢美食和拍照');

    expect(parsed.destination).toBe('上海');
    expect(parsed.days).toBe(3);
    expect(parsed.interests).toEqual(expect.arrayContaining(['当地美食', '拍照打卡']));
    expect(parsed.leftovers).toBe('');
  });

  it('没有结构化信息时 matched=false，原话留给特别要求', () => {
    const parsed = parsePlanPrompt('酒店离地铁近一点，晚上想逛街');

    expect(parsed.matched).toBe(false);
    expect(parsed.destination).toBeNull();
    expect(parsed.days).toBeNull();

    const form = applyParsedToForm(emptyPlanForm(), parsed, '酒店离地铁近一点，晚上想逛街');
    expect(form.notes).toBe('酒店离地铁近一点，晚上想逛街');
    expect(form.destination).toBe('');
  });

  it('空输入不崩、不误判', () => {
    const parsed = parsePlanPrompt('   ');
    expect(parsed.matched).toBe(false);
    expect(parsed.days).toBeNull();
    expect(parsed.leftovers).toBe('');
  });
});

describe('applyParsedToForm · 回填表单', () => {
  it('把解析到的字段写进表单，未识别的要求进特别要求', () => {
    const parsed = parsePlanPrompt('上海两日游，2人，预算3000，酒店离地铁近一点');
    const form = applyParsedToForm(
      emptyPlanForm(),
      parsed,
      '上海两日游，2人，预算3000，酒店离地铁近一点',
    );

    expect(form.mode).toBe('scratch');
    expect(form.destination).toBe('上海');
    expect(form.days).toBe(2);
    expect(form.adults).toBe(2);
    expect(form.budgetEnabled).toBe(true);
    expect(form.budget).toBe(3000);
    // 只有没被字段吸收的那半句留在特别要求里
    expect(form.notes).toContain('地铁');
    expect(form.notes).not.toContain('上海两日游');
  });
});

describe('describeParsed · 已识别摘要', () => {
  it('按「目的地 · 天数 · 人数 · 预算」顺序给出一行摘要', () => {
    const parsed = parsePlanPrompt('上海两日游，2人，预算3000元');
    expect(describeParsed(parsed)).toEqual(['上海', '2 天', '2 人', '预算 ¥3000']);
  });
});
