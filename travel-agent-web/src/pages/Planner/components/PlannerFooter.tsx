import { Button } from 'antd';
import type { PlannerStep } from '../planDraft';

interface PrimaryActionProps {
  /** 桌面页脚用 large，移动端固定底栏用 middle */
  large: boolean;
  step: PlannerStep;
  streaming: boolean;
  /** 第 1、2 步的门禁文案；有值时「生成计划」禁用，不让用户发送不完整需求 */
  blockingIssue: string | null;
  onNext: () => void;
  onSubmit: () => void;
}

/** 主操作：第 1、2 步是「继续」，第 3 步是「生成计划」 */
export function PlannerPrimaryAction({
  large,
  step,
  streaming,
  blockingIssue,
  onNext,
  onSubmit,
}: PrimaryActionProps) {
  const size: 'large' | 'middle' = large ? 'large' : 'middle';
  if (step < 3) {
    return (
      <Button type="primary" size={size} data-testid="planner-next" onClick={onNext}>
        继续
      </Button>
    );
  }
  return (
    <Button
      type="primary"
      size={size}
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
  isMobile: boolean;
  step: PlannerStep;
  streaming: boolean;
  blockingIssue: string | null;
  onBack: () => void;
  onSaveDraft: () => void;
  onResetAll: () => void;
  onNext: () => void;
  onSubmit: () => void;
}

/**
 * 卡片页脚：次级动作（清空全部需求 / 保存草稿）常驻；
 * 主操作按钮在桌面出现在这里，移动端改由固定底栏承载（两处只渲染其中一处）。
 */
export default function PlannerFooter({
  isMobile,
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
      {!isMobile && (
        <div className="plan3-footer-main">
          {step > 1 && (
            <Button data-testid="planner-back" onClick={onBack} disabled={streaming}>
              上一步
            </Button>
          )}
          <PlannerPrimaryAction
            large
            step={step}
            streaming={streaming}
            blockingIssue={blockingIssue}
            onNext={onNext}
            onSubmit={onSubmit}
          />
        </div>
      )}
    </div>
  );
}

interface MobileBarProps {
  step: PlannerStep;
  streaming: boolean;
  blockingIssue: string | null;
  onBack: () => void;
  onNext: () => void;
  onSubmit: () => void;
}

/** 移动端固定底栏：上一步 + 当前步骤 + 主操作，避免主按钮被表单推到屏幕外 */
export function PlannerMobileBar({
  step,
  streaming,
  blockingIssue,
  onBack,
  onNext,
  onSubmit,
}: MobileBarProps) {
  return (
    <div className="plan3-mobile-bar">
      <Button data-testid="planner-back" onClick={onBack} disabled={step === 1 || streaming}>
        上一步
      </Button>
      <span className="plan3-mobile-step">步骤 {step}/3</span>
      <PlannerPrimaryAction
        large={false}
        step={step}
        streaming={streaming}
        blockingIssue={blockingIssue}
        onNext={onNext}
        onSubmit={onSubmit}
      />
    </div>
  );
}
