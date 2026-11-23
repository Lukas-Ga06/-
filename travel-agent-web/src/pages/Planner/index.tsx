import { useEffect, useMemo, useRef, useState } from 'react';
import { App, Button } from 'antd';
import { ArrowLeftOutlined, WarningOutlined } from '@ant-design/icons';
import { useNavigate, useSearchParams } from 'react-router-dom';
import PlanDiff from '../../components/PlanDiff';
import { useAgentStore } from '../../store/agentStore';
import { useIsMobile } from '../../hooks/useMediaQuery';
import { useAgentScope } from '../../hooks/useAgentScope';
import {
  clampPlanMessage,
  compilePlanPrompt,
  compilePlanSummary,
  describeRequirementRows,
  emptyPlanForm,
  isFormSubmittable,
  type PlanFormState,
} from './planForm';
import {
  applyParsedToForm,
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
  readDraft,
  saveDraft,
  stepIssue,
  type PlanDraft,
  type PlannerStep,
} from './planDraft';
import PlanStepShell from './components/PlanStepShell';
import PlanStepBasics from './components/PlanStepBasics';
import PlanStepPreferences from './components/PlanStepPreferences';
import { PlanStepConfirm, RequirementAside } from './components/PlanReview';
import PlannerFooter, { PlannerMobileBar } from './components/PlannerFooter';
import WorkspacePane from './components/WorkspacePane';
import './planner.css';

/**
 * 规划页（P1-03 / §8.3）：三步填完需求 → Agent 生成 → 对话局部调整。
 * 三步各自只问一段信息，第 1 步是唯一必填步骤，其余可整段跳过。
 *
 * 页面本身只负责：步骤状态与门禁、草稿读写、需求编译与提交，以及视图切换；
 * 各步骤的表单、只读摘要、页脚/移动端操作栏与工作区在 components/ 下。
 */
export default function Planner() {
  const navigate = useNavigate();
  const [params] = useSearchParams();
  const { message } = App.useApp();
  const isMobile = useIsMobile();
  // 会话隔离（阶段 C）：规划器有自己的对话作用域；进入/离开不再 reset 整个 store
  useAgentScope('planner');
  const streaming = useAgentStore((s) => s.streaming);
  const messages = useAgentStore((s) => s.messages);
  const pendingConfirm = useAgentStore((s) => s.pendingConfirm);
  const confirm = useAgentStore((s) => s.confirm);
  const cancel = useAgentStore((s) => s.cancel);
  const committedTripId = useAgentStore((s) => s.committedTripId);
  const clearCommitted = useAgentStore((s) => s.clearCommitted);
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
          <WorkspacePane form={form} streaming={streaming} onBackToForm={backToForm} />
        ) : (
          <div className="plan3-layout fade-up">
            <div className="plan3-card">
              <div className="plan3-scroll">
                <PlanStepShell
                  step={step}
                  initialPrompt={initialPrompt}
                  restorableDraft={restorableDraft}
                  onRestoreDraft={restoreDraft}
                  onDiscardDraft={discardDraft}
                />

                {step === 1 && (
                  <PlanStepBasics
                    key="step-1"
                    form={form}
                    patch={patch}
                    parsedPrompt={parsedPrompt}
                    onClearParsed={clearParsed}
                  />
                )}
                {step === 2 && <PlanStepPreferences key="step-2" form={form} patch={patch} />}
                {step === 3 && (
                  <PlanStepConfirm
                    key="step-3"
                    requirementRows={requirementRows}
                    parsedPrompt={parsedPrompt}
                    pendingItems={pendingItems}
                    blockingIssue={blockingIssue}
                  />
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

              <PlannerFooter
                isMobile={isMobile}
                step={step}
                streaming={streaming}
                blockingIssue={blockingIssue}
                onBack={goBack}
                onSaveDraft={handleSaveDraft}
                onResetAll={resetAll}
                onNext={goNext}
                onSubmit={submit}
              />
            </div>

            {!isMobile && (
              <RequirementAside asideRows={asideRows} missingRequired={missingRequired} />
            )}
          </div>
        )}
      </div>

      {!inWorkspace && isMobile && (
        <PlannerMobileBar
          step={step}
          streaming={streaming}
          blockingIssue={blockingIssue}
          onBack={goBack}
          onNext={goNext}
          onSubmit={submit}
        />
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
