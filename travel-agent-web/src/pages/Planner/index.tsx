import { useEffect, useMemo, useRef, useState } from 'react';
import { App, Button, Checkbox, DatePicker, Input, InputNumber, Radio } from 'antd';
import {
  ArrowLeftOutlined,
  DeleteOutlined,
  PlusOutlined,
  WarningOutlined,
} from '@ant-design/icons';
import { useNavigate, useSearchParams } from 'react-router-dom';
import dayjs, { type Dayjs } from 'dayjs';
import ChatPanel from '../../components/ChatPanel';
import PlanDiff from '../../components/PlanDiff';
import { useAgentStore } from '../../store/agentStore';
import { useIsMobile } from '../../hooks/useMediaQuery';
import {
  clampPlanMessage,
  compilePlanPrompt,
  compilePlanSummary,
  describeRequirementRows,
  emptyPlanForm,
  FOCUS_OPTIONS,
  GUIDE_MAX_CHARS,
  GUIDES_MAX_TOTAL,
  guidesTotalChars,
  INTEREST_OPTIONS,
  isFormSubmittable,
  type CompanionType,
  type Pace,
  type PlanFormState,
} from './planForm';
import {
  applyParsedToForm,
  describeParsed,
  lowConfidenceHints,
  parsePlanPrompt,
  type ParsedPlanPrompt,
} from './parsePrompt';
import {
  clearDraft,
  createDraft,
  DRAFT_SAVE_DELAY_MS,
  hasRequirementInput,
  isDraftUsable,
  nextStep,
  prevStep,
  PLANNER_STEPS,
  readDraft,
  saveDraft,
  stepIssue,
  type PlanDraft,
  type PlannerStep,
} from './planDraft';
import './planner.css';

const COMPANION_CHIPS: { key: CompanionType; label: string }[] = [
  { key: 'alone', label: '独自' },
  { key: 'partner', label: '伴侣' },
  { key: 'friends', label: '朋友' },
  { key: 'parents', label: '带父母' },
  { key: 'kids', label: '带儿童' },
  { key: 'other', label: '其他' },
];

const PACE_CHIPS: { key: Pace; label: string; desc: string }[] = [
  { key: 'relaxed', label: '轻松悠闲', desc: '行程留白更多' },
  { key: 'balanced', label: '张弛有度', desc: '重点体验与休息兼顾' },
  { key: 'packed', label: '尽量多玩', desc: '接受更高行程密度' },
];

/** 每一步的标题与一句说明：文案集中在这里，步骤切换只看 step */
const STEP_COPY: Record<PlannerStep, { title: string; desc: string }> = {
  1: {
    title: '基本信息',
    desc: '目的地、出行时间和同行人数，用来确定行程的基本范围',
  },
  2: {
    title: '偏好与约束',
    desc: '都可以跳过；填得越具体，方案越贴近你的真实决策',
  },
  3: {
    title: '确认需求',
    desc: '核对结构化摘要与待确认项，确认无误后生成计划',
  },
};

/**
 * 规划页（P1-03 / §8.3）：三步填完需求 → Agent 生成 → 对话局部调整。
 * 三步各自只问一段信息，第 1 步是唯一必填步骤，其余可整段跳过。
 */
export default function Planner() {
  const navigate = useNavigate();
  const [params] = useSearchParams();
  const { message } = App.useApp();
  const isMobile = useIsMobile();
  const streaming = useAgentStore((s) => s.streaming);
  const messages = useAgentStore((s) => s.messages);
  const pendingConfirm = useAgentStore((s) => s.pendingConfirm);
  const confirm = useAgentStore((s) => s.confirm);
  const cancel = useAgentStore((s) => s.cancel);
  const committedTripId = useAgentStore((s) => s.committedTripId);
  const clearCommitted = useAgentStore((s) => s.clearCommitted);
  const reset = useAgentStore((s) => s.reset);
  const newConversation = useAgentStore((s) => s.newConversation);
  const send = useAgentStore((s) => s.send);

  // 从旅行页「一句话规划」跳来时，先把那句话解析成结构化需求并回填表单（P1-02）：
  // 已识别的目的地/天数/人数/预算不再要求用户重复填写，只有残余要求进「特别要求」。
  const initialPrompt = params.get('prompt') ?? '';
  const initialParsed = useMemo<ParsedPlanPrompt | null>(
    () => (initialPrompt ? parsePlanPrompt(initialPrompt) : null),
    [initialPrompt],
  );
  const [parsedPrompt, setParsedPrompt] = useState<ParsedPlanPrompt | null>(initialParsed);
  const [form, setForm] = useState<PlanFormState>(() =>
    initialPrompt && initialParsed
      ? applyParsedToForm(emptyPlanForm(), initialParsed, initialPrompt)
      : emptyPlanForm(),
  );
  const [step, setStep] = useState<PlannerStep>(1);
  /** 当前步骤的门禁文案（例如「先填写目的地」），为空表示没有待解决的提示 */
  const [stepError, setStepError] = useState<string | null>(null);
  /**
   * 有可用草稿时只提示、不自动恢复：
   * 用户可能是带着一句话需求进来的，静默覆盖当前表单比多问一句更糟。
   */
  const [restorableDraft, setRestorableDraft] = useState<PlanDraft | null>(() => {
    const stored = readDraft();
    return isDraftUsable(stored) ? stored : null;
  });
  /** 已提交进入「规划工作区」（表单让位给对话） */
  const [started, setStarted] = useState(false);
  const submittedRef = useRef(false);

  useEffect(() => {
    reset();
    return () => reset();
  }, []);

  useEffect(() => {
    if (committedTripId) {
      const id = committedTripId;
      clearCommitted();
      navigate(`/trip/${id}`);
    }
  }, [committedTripId]);

  // §8.3「离开时若生成中要提示」：草稿本身有自动保存兜底，
  // 但流式生成中的回复只存在内存里，刷新就没了，所以只在生成中拦截一次。
  useEffect(() => {
    if (!streaming) return;
    const onBeforeUnload = (event: BeforeUnloadEvent) => {
      event.preventDefault();
      event.returnValue = '';
    };
    window.addEventListener('beforeunload', onBeforeUnload);
    return () => window.removeEventListener('beforeunload', onBeforeUnload);
  }, [streaming]);

  // 自动保存：只在「没有待恢复草稿、还没提交」时写，避免把提示中的旧草稿覆盖掉。
  // 空白表单不产生草稿，否则每次进规划页都会留下一条没有内容的「未完成规划」。
  const canAutosave = !started && restorableDraft === null;
  useEffect(() => {
    if (!canAutosave) return;
    if (!hasRequirementInput(form)) {
      clearDraft();
      return;
    }
    const timer = window.setTimeout(() => {
      saveDraft(createDraft(form, step));
    }, DRAFT_SAVE_DELAY_MS);
    return () => window.clearTimeout(timer);
  }, [canAutosave, form, step]);

  const patch = (p: Partial<PlanFormState>) => {
    setForm((prev) => ({ ...prev, ...p }));
    setStepError(null);
    // 用户开始编辑当前表单，说明已经选择了「按现在这份继续」而不是恢复旧草稿；
    // 关掉提示，自动保存才能接管（否则提示会一直挡着草稿写入）。
    setRestorableDraft(null);
  };

  const inWorkspace = started || messages.length > 0 || streaming;

  /** 第 1、2 步的硬门禁：有文案就停在当前步，不发任何请求 */
  const blockingIssue = stepIssue(1, form) ?? stepIssue(2, form);

  const requirementRows = describeRequirementRows(form);
  const missingRequired = requirementRows
    .filter((row) => row.required && !row.value)
    .map((row) => row.label);
  // 右侧摘要只展示「已填内容 + 必填项」：把整张表都摆上去会变成第二份表单
  const asideRows = requirementRows.filter((row) => row.value || row.required);

  /** 第 3 步的待核实项：解析置信度不足的字段 + 方案会替用户做的假设 */
  const pendingItems = useMemo(() => {
    const items = parsedPrompt ? lowConfidenceHints(parsedPrompt) : [];
    if (!(form.startDate && form.endDate)) {
      items.push('没有具体日期：行程按天数安排，机票与门票价格无法核对');
    }
    if (!form.budgetEnabled || form.budget == null) {
      items.push('没有设置预算：方案按常规价位推荐，生成后可以再压预算');
    }
    return items;
  }, [parsedPrompt, form]);

  const submit = () => {
    if (streaming || submittedRef.current) return;
    // 第 3 步理论上不会带着缺失字段进来（第 1/2 步已经拦过），这里再做一次防御：
    // 恢复到一条不完整的草稿时，按钮是禁用 + 说明原因，不能直接发请求。
    if (blockingIssue) {
      setStepError(blockingIssue);
      message.warning(blockingIssue);
      return;
    }
    if (!isFormSubmittable(form)) {
      message.warning(form.mode === 'guides' ? '至少粘贴一篇攻略正文' : '请填写出行时间');
      return;
    }
    submittedRef.current = true;
    setStepError(null);
    // 需求已经提交：草稿使命结束，否则下次进入会提示恢复一份已经生成过的需求
    clearDraft();
    send({
      message: clampPlanMessage(compilePlanPrompt(form)),
      displayText: compilePlanSummary(form),
      transport: 'post',
    });
    setStarted(true);
  };

  const goNext = () => {
    const issue = stepIssue(step, form);
    if (issue) {
      setStepError(issue);
      return;
    }
    setStepError(null);
    setStep(nextStep(step));
  };

  const goBack = () => {
    setStepError(null);
    setStep(prevStep(step));
  };

  const handleSaveDraft = () => {
    const saved = saveDraft(createDraft(form, step));
    if (saved) {
      message.success('草稿已保存，下次进入可继续');
    } else {
      message.warning('当前浏览器无法保存草稿（可能禁用了本地存储）');
    }
  };

  const restoreDraft = () => {
    if (!restorableDraft) return;
    setForm(restorableDraft.form);
    setStep(restorableDraft.step);
    // 草稿里的字段才是用户真正编辑过的版本，一句话解析结果已经过期
    setParsedPrompt(null);
    setStepError(null);
    setRestorableDraft(null);
    message.success('已恢复上次未完成的规划');
  };

  const discardDraft = () => {
    clearDraft();
    setRestorableDraft(null);
  };

  /** 清空整张表单：同时清掉解析结果与草稿，回到完全空白的第一步 */
  const resetAll = () => {
    setForm(emptyPlanForm());
    setParsedPrompt(null);
    setStep(1);
    setStepError(null);
    clearDraft();
  };

  /** 放弃自动识别结果：回到空白表单，原话也不再塞进特别要求 */
  const clearParsed = () => {
    setParsedPrompt(null);
    setForm(emptyPlanForm());
    setStep(1);
    setStepError(null);
  };

  const backToForm = () => {
    submittedRef.current = false;
    setStarted(false);
    // 返回修改需求意味着重新提交会创建新方案：旧会话历史一并清掉
    newConversation();
  };

  const guidesTotal = guidesTotalChars(form.guides);
  const timeMode = form.startDate && form.endDate ? 'dates' : 'days';
  const parsedParts = parsedPrompt ? describeParsed(parsedPrompt) : [];
  const parsedHints = parsedPrompt ? lowConfidenceHints(parsedPrompt) : [];

  /** 主操作按钮：桌面在页脚、移动端在固定底栏，两处只渲染其中一处 */
  const primaryAction = (large: boolean) => {
    const size: 'large' | 'middle' = large ? 'large' : 'middle';
    if (step < 3) {
      return (
        <Button type="primary" size={size} data-testid="planner-next" onClick={goNext}>
          继续
        </Button>
      );
    }
    return (
      <Button
        type="primary"
        size={size}
        data-testid="planner-submit"
        onClick={submit}
        loading={streaming}
        disabled={Boolean(blockingIssue)}
      >
        生成计划
      </Button>
    );
  };

  return (
    <div className="planner-page">
      <div className="trip-header">
        <Button
          type="text"
          icon={<ArrowLeftOutlined />}
          aria-label="返回首页"
          onClick={() => navigate('/')}
        />
        <div className="th-title">
          <b>规划新行程</b>
          <span className="th-meta">
            填写需求或粘贴攻略，Agent 会自动完成合并、搜索、排期与预算
          </span>
        </div>
      </div>
      <div className="planner-body">
        {inWorkspace ? (
          <div className="planner-card fade-up">
            <div className="chat-head">
              <div className="ch-avatar">🤖</div>
              <div>
                <b>AI 旅行规划师</b>
                <span>正在按你提交的需求生成方案，可继续对话局部调整</span>
              </div>
            </div>
            <div className="plan-summary-wrap">
              <div className="plan-summary">
                <span className="plan-summary-label">当前需求</span>
                <span className="chip">
                  {compilePlanSummary(form).replace('结构化需求提交：', '')}
                </span>
                <span className="plan-summary-spacer" />
                <Button size="small" onClick={backToForm} disabled={streaming}>
                  返回修改需求
                </Button>
              </div>
            </div>
            <ChatPanel placeholder="继续调整，如：第二天太赶了，删掉一个景点" />
          </div>
        ) : (
          <div className="plan3-layout fade-up">
            <div className="plan3-card">
              <div className="plan3-scroll">
                <div className="plan3-step-head">
                  <div className="plan3-step-copy">
                    <span className="plan3-step-kicker" data-testid="planner-step-indicator">
                      步骤 {step}/3
                    </span>
                    <h2 className="plan3-step-title">{STEP_COPY[step].title}</h2>
                    <p className="plan3-step-desc">{STEP_COPY[step].desc}</p>
                  </div>
                  <ol className="plan3-progress" aria-label="规划进度">
                    {PLANNER_STEPS.map((s) => (
                      <li
                        key={s}
                        className={s < step ? 'done' : s === step ? 'current' : ''}
                        aria-current={s === step ? 'step' : undefined}
                      />
                    ))}
                  </ol>
                </div>

                {restorableDraft && (
                  <div className="plan3-draft" role="status">
                    <div className="plan3-draft-copy">
                      <b>恢复上次未完成规划？</b>
                      <span>
                        {initialPrompt
                          ? '本次已按一句话需求填好，恢复会覆盖这些内容。'
                          : '检测到上次没有提交的需求，可以接着改。'}
                        最后编辑 {dayjs(restorableDraft.updatedAt).format('MM-DD HH:mm')}，停在步骤{' '}
                        {restorableDraft.step}/3。
                      </span>
                    </div>
                    <div className="plan3-draft-actions">
                      <Button
                        size="small"
                        type="primary"
                        data-testid="planner-restore-draft"
                        onClick={restoreDraft}
                      >
                        恢复
                      </Button>
                      <Button
                        size="small"
                        type="text"
                        data-testid="planner-discard-draft"
                        onClick={discardDraft}
                      >
                        忽略
                      </Button>
                    </div>
                  </div>
                )}

                {/* ---------- 步骤 1：基本信息（唯一必填步骤） ---------- */}
                {step === 1 && (
                  <div className="plan3-step-body" key="step-1">
                    {parsedPrompt?.matched && (
                      <div className="plan3-parsed" data-testid="planner-parsed">
                        <div className="plan3-parsed-head">
                          <b>已识别</b>
                          <span>这些字段已经填好，核对或修改后继续</span>
                          <span className="plan3-spacer" />
                          <Button size="small" type="text" onClick={clearParsed}>
                            清除并手动填写
                          </Button>
                        </div>
                        <div className="plan3-chips">
                          {parsedParts.map((part) => (
                            <span key={part} className="plan3-chip">
                              {part}
                            </span>
                          ))}
                        </div>
                        {parsedHints.length > 0 && (
                          <ul className="plan3-hints">
                            {parsedHints.map((hint) => (
                              <li key={hint}>
                                <WarningOutlined aria-hidden="true" /> {hint}
                              </li>
                            ))}
                          </ul>
                        )}
                        {parsedPrompt.conflicts.map((conflict) => (
                          <div className="plan3-conflict" role="alert" key={conflict}>
                            {conflict}
                          </div>
                        ))}
                      </div>
                    )}

                    <div className="plan3-sec">
                      <div className="plan3-sec-head">
                        <b>目的地</b>
                        <span className="plan3-required">必填</span>
                        <span>城市或地区，例如「东京」「云南大理」</span>
                      </div>
                      <div className="plan3-row">
                        <div className="plan3-field-grow">
                          <Input
                            data-testid="planner-destination"
                            placeholder="目的地，例如：东京、巴黎、伦敦"
                            value={form.destination}
                            maxLength={60}
                            onChange={(e) => patch({ destination: e.target.value })}
                          />
                        </div>
                      </div>
                    </div>

                    <div className="plan3-sec">
                      <div className="plan3-sec-head">
                        <b>出行时间</b>
                        <span className="plan3-required">必填</span>
                        <span>旅行天数或具体日期，至少给一个</span>
                      </div>
                      <div className="plan3-row">
                        <Radio.Group
                          value={timeMode}
                          onChange={(e) =>
                            patch(
                              e.target.value === 'dates'
                                ? {
                                    days: null,
                                    startDate: form.startDate ?? dayjs().format('YYYY-MM-DD'),
                                    endDate:
                                      form.endDate ?? dayjs().add(2, 'day').format('YYYY-MM-DD'),
                                  }
                                : { startDate: null, endDate: null, days: form.days ?? 3 },
                            )
                          }
                          optionType="button"
                          buttonStyle="solid"
                          options={[
                            { value: 'days', label: '旅行天数' },
                            { value: 'dates', label: '具体日期' },
                          ]}
                        />
                        {timeMode === 'days' ? (
                          <InputNumber
                            data-testid="planner-days"
                            min={1}
                            max={30}
                            value={form.days ?? undefined}
                            placeholder="几天？"
                            addonAfter="天"
                            className="plan3-num"
                            onChange={(v) => patch({ days: v ?? null })}
                          />
                        ) : (
                          <DatePicker.RangePicker
                            value={
                              form.startDate && form.endDate
                                ? ([dayjs(form.startDate), dayjs(form.endDate)] as [Dayjs, Dayjs])
                                : null
                            }
                            onChange={(range) =>
                              patch({
                                startDate: range?.[0]?.format('YYYY-MM-DD') ?? null,
                                endDate: range?.[1]?.format('YYYY-MM-DD') ?? null,
                              })
                            }
                          />
                        )}
                      </div>
                    </div>

                    <div className="plan3-sec">
                      <div className="plan3-sec-head">
                        <b>同行人员</b>
                        <span>用于判断节奏、房型和活动适配度</span>
                      </div>
                      <div className="plan3-chips">
                        {COMPANION_CHIPS.map((c) => (
                          <button
                            type="button"
                            key={c.key}
                            aria-pressed={form.companion === c.key}
                            className={`plan3-chip-btn ${form.companion === c.key ? 'on' : ''}`}
                            onClick={() => patch({ companion: c.key })}
                          >
                            {c.label}
                          </button>
                        ))}
                      </div>
                      <div className="plan3-row">
                        <span className="plan3-label">成人</span>
                        <InputNumber
                          data-testid="planner-adults"
                          min={1}
                          max={20}
                          value={form.adults}
                          className="plan3-num-sm"
                          onChange={(v) => patch({ adults: v ?? 1 })}
                        />
                        <span className="plan3-label">儿童</span>
                        <InputNumber
                          min={0}
                          max={20}
                          value={form.kids}
                          className="plan3-num-sm"
                          onChange={(v) => patch({ kids: v ?? 0 })}
                        />
                      </div>
                    </div>
                  </div>
                )}

                {/* ---------- 步骤 2：偏好与约束（全部可选） ---------- */}
                {step === 2 && (
                  <div className="plan3-step-body" key="step-2">
                    <div className="plan3-sec">
                      <div className="plan3-sec-head">
                        <b>规划方式</b>
                        <span>两种方式都会结合下面的偏好与约束</span>
                      </div>
                      <div className="plan3-mode-grid">
                        <button
                          type="button"
                          aria-pressed={form.mode === 'guides'}
                          className={`plan3-mode ${form.mode === 'guides' ? 'on' : ''}`}
                          onClick={() => patch({ mode: 'guides' })}
                        >
                          <b>我已经收藏了攻略</b>
                          <span>
                            粘贴最多三篇小红书攻略、朋友行程或自己的草稿，我会合并、筛选和重新安排。
                          </span>
                        </button>
                        <button
                          type="button"
                          aria-pressed={form.mode === 'scratch'}
                          className={`plan3-mode ${form.mode === 'scratch' ? 'on' : ''}`}
                          onClick={() => patch({ mode: 'scratch' })}
                        >
                          <b>我还没有攻略</b>
                          <span>告诉我目的地、时间和偏好，帮我从零建立旅行计划。</span>
                        </button>
                      </div>
                    </div>

                    {form.mode === 'guides' && (
                      <div className="plan3-sec">
                        <div className="plan3-sec-head">
                          <b>添加你收藏的攻略</b>
                          <span>可以是完整行程，也可以只是美食、景点、拍照点或当地活动</span>
                        </div>
                        <div className="plan3-guides">
                          {form.guides.map((g, i) => (
                            <div className="plan3-guide" key={i}>
                              <div className="plan3-guide-head">
                                <span>参考攻略 {i + 1}</span>
                                {form.guides.length > 1 && (
                                  <Button
                                    size="small"
                                    type="text"
                                    danger
                                    icon={<DeleteOutlined />}
                                    onClick={() =>
                                      patch({ guides: form.guides.filter((_, j) => j !== i) })
                                    }
                                  >
                                    删除本篇
                                  </Button>
                                )}
                              </div>
                              <div className="plan3-guide-title">
                                <Input
                                  placeholder="标题或来源（选填），如：小红书｜伦敦五日景点攻略"
                                  value={g.title}
                                  maxLength={120}
                                  onChange={(e) =>
                                    patch({
                                      guides: form.guides.map((x, j) =>
                                        j === i ? { ...x, title: e.target.value } : x,
                                      ),
                                    })
                                  }
                                />
                              </div>
                              <Input.TextArea
                                rows={5}
                                placeholder="粘贴攻略正文……"
                                value={g.content}
                                maxLength={GUIDE_MAX_CHARS}
                                showCount
                                onChange={(e) =>
                                  patch({
                                    guides: form.guides.map((x, j) =>
                                      j === i ? { ...x, content: e.target.value } : x,
                                    ),
                                  })
                                }
                              />
                            </div>
                          ))}
                        </div>
                        <div className="plan3-row plan3-row-between">
                          <Button
                            icon={<PlusOutlined />}
                            disabled={form.guides.length >= 3}
                            onClick={() =>
                              patch({ guides: [...form.guides, { title: '', content: '' }] })
                            }
                          >
                            再添加一篇攻略
                          </Button>
                          <span className="plan3-counter">
                            三篇正文合计 {guidesTotal}/{GUIDES_MAX_TOTAL} 字
                          </span>
                        </div>
                        <div className="plan3-sec-head plan3-sec-sub">
                          <b>你希望重点优化什么？</b>
                          <span>可多选，也可以暂不选择</span>
                        </div>
                        <div className="plan3-chips">
                          {FOCUS_OPTIONS.map((f) => (
                            <button
                              type="button"
                              key={f}
                              aria-pressed={form.focus.includes(f)}
                              className={`plan3-chip-btn ${form.focus.includes(f) ? 'on' : ''}`}
                              onClick={() =>
                                patch({
                                  focus: form.focus.includes(f)
                                    ? form.focus.filter((x) => x !== f)
                                    : [...form.focus, f],
                                })
                              }
                            >
                              {f}
                            </button>
                          ))}
                        </div>
                      </div>
                    )}

                    <div className="plan3-sec">
                      <div className="plan3-sec-head">
                        <b>预算与节奏</b>
                        <span>可选信息，帮助方案更贴近真实决策</span>
                      </div>
                      <div className="plan3-row">
                        <Checkbox
                          checked={form.budgetEnabled}
                          onChange={(e) => patch({ budgetEnabled: e.target.checked })}
                        >
                          设置旅行预算
                        </Checkbox>
                        {form.budgetEnabled && (
                          <InputNumber
                            min={100}
                            max={1000000}
                            step={100}
                            value={form.budget ?? undefined}
                            placeholder="总预算"
                            addonBefore="¥"
                            className="plan3-num"
                            onChange={(v) => patch({ budget: v ?? null })}
                          />
                        )}
                      </div>
                      <div className="plan3-pace-grid">
                        {PACE_CHIPS.map((p) => (
                          <button
                            type="button"
                            key={p.key}
                            aria-pressed={form.pace === p.key}
                            className={`plan3-mode ${form.pace === p.key ? 'on' : ''}`}
                            onClick={() => patch({ pace: p.key })}
                          >
                            <b>{p.label}</b>
                            <span>{p.desc}</span>
                          </button>
                        ))}
                      </div>
                      <div className="plan3-sec-head plan3-sec-sub">
                        <b>想体验什么？</b>
                        <span>可多选，也可以不选</span>
                      </div>
                      <div className="plan3-chips">
                        {INTEREST_OPTIONS.map((it) => (
                          <button
                            type="button"
                            key={it}
                            aria-pressed={form.interests.includes(it)}
                            className={`plan3-chip-btn ${form.interests.includes(it) ? 'on' : ''}`}
                            onClick={() =>
                              patch({
                                interests: form.interests.includes(it)
                                  ? form.interests.filter((x) => x !== it)
                                  : [...form.interests, it],
                              })
                            }
                          >
                            {it}
                          </button>
                        ))}
                      </div>
                    </div>

                    <div className="plan3-sec">
                      <div className="plan3-sec-head">
                        <b>哪些内容不能被修改？</b>
                        <span>例如已经购买的门票、必须去的景点，或已确定的住宿位置</span>
                      </div>
                      <div className="plan3-row">
                        <div className="plan3-field-grow">
                          <Input.TextArea
                            rows={2}
                            placeholder="例如：第三天下午已购买大英博物馆特展门票，请勿移动。"
                            value={form.locked}
                            maxLength={300}
                            onChange={(e) => patch({ locked: e.target.value })}
                          />
                        </div>
                      </div>
                      <div className="plan3-sec-head plan3-sec-sub">
                        <b>特别要求</b>
                        <span>其它希望我记住的偏好</span>
                      </div>
                      <div className="plan3-row">
                        <div className="plan3-field-grow">
                          <Input.TextArea
                            data-testid="planner-notes"
                            rows={2}
                            placeholder="例如：和父母同行，希望少走路；不吃辣；酒店靠近地铁。"
                            value={form.notes}
                            maxLength={500}
                            onChange={(e) => patch({ notes: e.target.value })}
                          />
                        </div>
                      </div>
                    </div>
                  </div>
                )}

                {/* ---------- 步骤 3：确认需求（只读摘要） ---------- */}
                {step === 3 && (
                  <div className="plan3-step-body" key="step-3">
                    <div className="plan3-summary" data-testid="planner-summary">
                      <div className="plan3-summary-card">
                        <dl className="plan3-rows">
                          {requirementRows.map((row) => (
                            <div className="plan3-sum-row" key={row.key}>
                              <dt>{row.label}</dt>
                              <dd>
                                {row.value || (
                                  <span className={row.required ? 'plan3-missing' : 'plan3-empty'}>
                                    {row.required ? '必填项还没填' : '未填写（可选）'}
                                  </span>
                                )}
                              </dd>
                            </div>
                          ))}
                        </dl>
                      </div>

                      {parsedPrompt && parsedPrompt.conflicts.length > 0 && (
                        <div className="plan3-pending">
                          <b>需要你确认的冲突</b>
                          {parsedPrompt.conflicts.map((conflict) => (
                            <div className="plan3-conflict" role="alert" key={conflict}>
                              {conflict}
                            </div>
                          ))}
                        </div>
                      )}

                      {pendingItems.length > 0 && (
                        <div className="plan3-pending">
                          <b>待核实字段</b>
                          <ul>
                            {pendingItems.map((item) => (
                              <li key={item}>{item}</li>
                            ))}
                          </ul>
                        </div>
                      )}

                      {blockingIssue && (
                        <div className="plan3-hint" role="alert">
                          <WarningOutlined aria-hidden="true" />
                          <span>{blockingIssue}，请返回上一步补齐后再生成计划。</span>
                        </div>
                      )}
                    </div>
                  </div>
                )}

                {stepError && (
                  <div
                    className="plan3-hint"
                    role="alert"
                    data-testid={step === 1 ? 'planner-required-hint' : undefined}
                  >
                    <WarningOutlined aria-hidden="true" />
                    <span>{stepError}</span>
                  </div>
                )}
              </div>

              <div className="plan3-footer">
                <div className="plan3-footer-secondary">
                  <Button type="text" onClick={resetAll}>
                    清空全部需求
                  </Button>
                  <Button data-testid="planner-save-draft" onClick={handleSaveDraft}>
                    保存草稿
                  </Button>
                </div>
                {!isMobile && (
                  <div className="plan3-footer-main">
                    {step > 1 && (
                      <Button data-testid="planner-back" onClick={goBack} disabled={streaming}>
                        上一步
                      </Button>
                    )}
                    {primaryAction(true)}
                  </div>
                )}
              </div>
            </div>

            {!isMobile && (
              <aside className="plan3-aside" aria-label="需求摘要">
                <div className="plan3-aside-head">
                  <b>需求摘要</b>
                  <span className="plan3-aside-badge">实时</span>
                </div>
                <dl className="plan3-aside-rows">
                  {asideRows.map((row) => (
                    <div className="plan3-aside-row" key={row.key}>
                      <dt>{row.label}</dt>
                      <dd>{row.value || '待填写'}</dd>
                    </div>
                  ))}
                </dl>
                <p className="plan3-aside-foot">
                  {missingRequired.length === 0
                    ? '必填信息已齐全，可以一路确认到生成。'
                    : `还差：${missingRequired.join('、')}`}
                </p>
              </aside>
            )}
          </div>
        )}
      </div>

      {!inWorkspace && isMobile && (
        <div className="plan3-mobile-bar">
          <Button data-testid="planner-back" onClick={goBack} disabled={step === 1 || streaming}>
            上一步
          </Button>
          <span className="plan3-mobile-step">步骤 {step}/3</span>
          {primaryAction(false)}
        </div>
      )}

      <PlanDiff
        open={!!pendingConfirm}
        result={pendingConfirm}
        onConfirm={(acknowledgeIssues?: boolean) => {
          confirm(acknowledgeIssues);
        }}
        onCancel={() => cancel()}
        loading={streaming}
      />
    </div>
  );
}
