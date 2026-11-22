import { describe, expect, it } from 'vitest';
import { emptyPlanForm, type PlanFormState } from './planForm';
import {
  clearDraft,
  createDraft,
  DRAFT_STORAGE_KEY,
  DRAFT_VERSION,
  hasGuideContent,
  hasPlanTime,
  hasRequirementInput,
  isDraftUsable,
  nextStep,
  PLANNER_STEPS,
  prevStep,
  readDraft,
  saveDraft,
  stepIssue,
  type DraftStorage,
} from './planDraft';

/** 内存存储：草稿逻辑不依赖真实 localStorage，测试也不该共享浏览器状态 */
class MemoryStorage implements DraftStorage {
  private readonly map = new Map<string, string>();

  getItem(key: string): string | null {
    return this.map.has(key) ? this.map.get(key)! : null;
  }

  setItem(key: string, value: string): void {
    this.map.set(key, value);
  }

  removeItem(key: string): void {
    this.map.delete(key);
  }
}

/** 读写都会抛异常的存储：模拟隐私模式/配额已满 */
const brokenStorage: DraftStorage = {
  getItem: () => {
    throw new Error('denied');
  },
  setItem: () => {
    throw new Error('quota');
  },
  removeItem: () => {
    throw new Error('denied');
  },
};

function filledForm(): PlanFormState {
  return {
    ...emptyPlanForm(),
    destination: '上海',
    days: 3,
    notes: '不吃辣',
    interests: ['当地美食'],
    guides: [{ title: '小红书｜上海三日', content: '外滩、豫园、陆家嘴' }],
  };
}

describe('planDraft · 草稿读写', () => {
  it('保存后读回同一份表单、步骤与更新时间', () => {
    const storage = new MemoryStorage();
    const form = filledForm();
    const draft = createDraft(form, 2, 1_700_000_000_000);

    expect(saveDraft(draft, storage)).toBe(true);

    const restored = readDraft(storage);
    expect(restored).not.toBeNull();
    expect(restored!.form).toEqual(form);
    expect(restored!.step).toBe(2);
    expect(restored!.updatedAt).toBe(1_700_000_000_000);
    expect(restored!.version).toBe(DRAFT_VERSION);
  });

  it('不传存储时用默认存储（浏览器里是 localStorage）', () => {
    localStorage.clear();
    expect(saveDraft(createDraft(filledForm(), 3))).toBe(true);
    expect(readDraft()?.form.destination).toBe('上海');

    clearDraft();
    expect(readDraft()).toBeNull();
    localStorage.clear();
  });

  it('清除后读不到草稿', () => {
    const storage = new MemoryStorage();
    saveDraft(createDraft(filledForm(), 1), storage);
    clearDraft(storage);
    expect(readDraft(storage)).toBeNull();
  });

  it('坏 JSON / 版本不符 / 字段缺失都返回 null，不把半截数据当草稿', () => {
    const storage = new MemoryStorage();
    const bad: string[] = [
      '{not json',
      'null',
      '[]',
      JSON.stringify({ version: 99, step: 1, updatedAt: 1, form: filledForm() }),
      JSON.stringify({ version: DRAFT_VERSION, step: 4, updatedAt: 1, form: filledForm() }),
      JSON.stringify({ version: DRAFT_VERSION, step: 1, updatedAt: '昨天', form: filledForm() }),
      // 字段缺失：只有 destination
      JSON.stringify({
        version: DRAFT_VERSION,
        step: 1,
        updatedAt: 1,
        form: { destination: '上海' },
      }),
      // 字段类型不对：interests 应为数组
      JSON.stringify({
        version: DRAFT_VERSION,
        step: 1,
        updatedAt: 1,
        form: { ...filledForm(), interests: '当地美食' },
      }),
      // 原型链上的 key 不能冒充合法同伴类型
      JSON.stringify({
        version: DRAFT_VERSION,
        step: 1,
        updatedAt: 1,
        form: { ...filledForm(), companion: 'toString' },
      }),
    ];
    for (const raw of bad) {
      storage.setItem(DRAFT_STORAGE_KEY, raw);
      expect(readDraft(storage), raw).toBeNull();
    }
  });

  it('存储不可用时不抛异常：写成 false、读取 null、清除静默', () => {
    expect(saveDraft(createDraft(filledForm(), 1), brokenStorage)).toBe(false);
    expect(readDraft(brokenStorage)).toBeNull();
    expect(() => clearDraft(brokenStorage)).not.toThrow();
  });

  it('空表单不算可用草稿，填过内容或进过第 2 步才算', () => {
    expect(hasRequirementInput(emptyPlanForm())).toBe(false);
    expect(hasRequirementInput(filledForm())).toBe(true);

    expect(isDraftUsable(null)).toBe(false);
    expect(isDraftUsable(createDraft(emptyPlanForm(), 1))).toBe(false);
    expect(isDraftUsable(createDraft(filledForm(), 1))).toBe(true);
    expect(isDraftUsable(createDraft(emptyPlanForm(), 2))).toBe(true);
  });
});

describe('planDraft · 步骤推进与门禁', () => {
  it('三步顺序固定，前进/回退在两端夹住', () => {
    expect(PLANNER_STEPS).toEqual([1, 2, 3]);
    expect(nextStep(1)).toBe(2);
    expect(nextStep(2)).toBe(3);
    expect(nextStep(3)).toBe(3);
    expect(prevStep(1)).toBe(1);
    expect(prevStep(2)).toBe(1);
    expect(prevStep(3)).toBe(2);
  });

  it('第 1 步：目的地与出行时间都填了才放行', () => {
    const form = emptyPlanForm();
    expect(stepIssue(1, form)).toBe('先填写目的地');

    form.destination = '上海';
    expect(stepIssue(1, form)).toBe('请填写出行时间');
    expect(hasPlanTime(form)).toBe(false);

    form.days = 3;
    expect(hasPlanTime(form)).toBe(true);
    expect(stepIssue(1, form)).toBeNull();

    // 用具体日期同样算填了时间
    const withDates: PlanFormState = {
      ...emptyPlanForm(),
      destination: '上海',
      startDate: '2026-11-21',
      endDate: '2026-11-23',
    };
    expect(hasPlanTime(withDates)).toBe(true);
    expect(stepIssue(1, withDates)).toBeNull();
  });

  it('第 1 步：同行人数为 0 时不能继续', () => {
    const form: PlanFormState = { ...emptyPlanForm(), destination: '上海', days: 2, adults: 0 };
    expect(stepIssue(1, form)).toBe('请填写同行人数');
  });

  it('第 2 步：攻略模式必须有正文，从零规划可整段留空', () => {
    const guidesForm: PlanFormState = { ...emptyPlanForm(), destination: '上海', days: 2 };
    expect(hasGuideContent(guidesForm)).toBe(false);
    expect(stepIssue(2, guidesForm)).toContain('攻略正文');

    guidesForm.guides = [{ title: '', content: '第一天外滩' }];
    expect(hasGuideContent(guidesForm)).toBe(true);
    expect(stepIssue(2, guidesForm)).toBeNull();

    const scratchForm: PlanFormState = {
      ...emptyPlanForm(),
      mode: 'scratch',
      destination: '上海',
      days: 2,
    };
    expect(stepIssue(2, scratchForm)).toBeNull();
  });

  it('第 3 步没有门禁（提交前由 isFormSubmittable 兜底）', () => {
    expect(stepIssue(3, emptyPlanForm())).toBeNull();
  });
});
