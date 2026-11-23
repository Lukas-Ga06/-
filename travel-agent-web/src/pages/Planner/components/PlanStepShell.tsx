import dayjs from 'dayjs';
import { Button } from 'antd';
import { PLANNER_STEPS, type PlanDraft, type PlannerStep } from '../planDraft';

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

interface Props {
  step: PlannerStep;
  /** 进入页面时带的一句话需求：决定草稿提示的文案（恢复会覆盖已填内容 / 可以接着改） */
  initialPrompt: string;
  /** 有可用草稿时只提示、不自动恢复；null 表示没有可恢复的草稿 */
  restorableDraft: PlanDraft | null;
  onRestoreDraft: () => void;
  onDiscardDraft: () => void;
}

/**
 * 步骤壳层：当前步骤的标题/进度 + 草稿恢复提示。
 * 只负责展示与回调，草稿的读取、写入、恢复判定都在页面里。
 */
export default function PlanStepShell({
  step,
  initialPrompt,
  restorableDraft,
  onRestoreDraft,
  onDiscardDraft,
}: Props) {
  return (
    <>
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
              onClick={onRestoreDraft}
            >
              恢复
            </Button>
            <Button
              size="small"
              type="text"
              data-testid="planner-discard-draft"
              onClick={onDiscardDraft}
            >
              忽略
            </Button>
          </div>
        </div>
      )}
    </>
  );
}
