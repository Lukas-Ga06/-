import { Button } from 'antd';
import type { PlannerStep } from '../planDraft';

interface PrimaryActionProps {
  step: PlannerStep;
  streaming: boolean;
  /** 第 1、2 步的门禁文案；有值时「生成计划」禁用，不让用户发送不完整需求 */
  blockingIssue: string | null;
  onNext: () => void;
  onSubmit: () => void;
}

/** 主操作：第 1、2 步是「继续」，第 3 步是「生成计划」 */
export function PlannerPrimaryAction({
  step,
  streaming,
  blockingIssue,
  onNext,
  onSubmit,
}: PrimaryActionProps) {
  if (step < 3) {
    return (
      <Button type="primary" size="large" data-testid="planner-next" onClick={onNext}>
        继续
      </Button>
    );
  }
  return (
    <Button
      type="primary"
      size="large"
      data-testid="planner-submit"
      onClick={onSubmit}
      loading={streaming}
      disabled={Boolean(blockingIssue)}
    >
      生成计划
    </Button>
  );
}

interface FooterProps {
  step: PlannerStep;
  streaming: boolean;
  blockingIssue: string | null;
  onBack: () => void;
  onSaveDraft: () => void;
  onResetAll: () => void;
  onNext: () => void;
  onSubmit: () => void;
}

/** 卡片页脚：次级动作（清空全部需求 / 保存草稿）常驻，主操作与「上一步」在右侧 */
export default function PlannerFooter({
  step,
  streaming,
  blockingIssue,
  onBack,
  onSaveDraft,
  onResetAll,
  onNext,
  onSubmit,
}: FooterProps) {
  return (
    <div className="plan3-footer">
      <div className="plan3-footer-secondary">
        <Button type="text" onClick={onResetAll}>
          清空全部需求
        </Button>
        <Button data-testid="planner-save-draft" onClick={onSaveDraft}>
          保存草稿
        </Button>
      </div>
      <div className="plan3-footer-main">
        {step > 1 && (
          <Button data-testid="planner-back" onClick={onBack} disabled={streaming}>
            上一步
          </Button>
        )}
        <PlannerPrimaryAction
          step={step}
          streaming={streaming}
          blockingIssue={blockingIssue}
          onNext={onNext}
          onSubmit={onSubmit}
        />
      </div>
    </div>
  );
}
