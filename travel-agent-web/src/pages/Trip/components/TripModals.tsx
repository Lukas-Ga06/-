import PlanDiff from '../../../components/PlanDiff';
import type { AgentResult } from '../../../types/agent';

interface Props {
  open: boolean;
  result: AgentResult | null;
  /** acknowledgeIssues：用户在确认弹窗里勾选了「接受未解决风险」（Batch 13） */
  onConfirm: (acknowledgeIssues?: boolean) => void;
  onCancel: () => void;
  loading: boolean;
}

/**
 * 方案变更确认弹窗：Agent 返回 NEED_CONFIRMATION 时出现，
 * 确认后的数据刷新（itinerary / route-legs）由页面负责。
 */
export default function TripModals({ open, result, onConfirm, onCancel, loading }: Props) {
  return (
    <PlanDiff
      open={open}
      result={result}
      onConfirm={onConfirm}
      onCancel={onCancel}
      loading={loading}
    />
  );
}
