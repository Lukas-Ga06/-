import { Alert, Modal, Tag } from 'antd';
import { ArrowRightOutlined } from '@ant-design/icons';
import type { AgentResult, DiffChange } from '../../types/agent';

interface Props {
  open: boolean;
  result: AgentResult | null;
  onConfirm: () => void;
  onCancel: () => void;
  loading?: boolean;
}

function renderChange(c: DiffChange) {
  const isRemove = c.type === 'REMOVE';
  const isModify = c.type === 'MODIFY';
  return (
    <div className={`diff-row ${isRemove ? 'remove' : 'add'}`}>
      <span className="dr-icon">{isRemove ? '✕' : isModify ? '✎' : '＋'}</span>
      <span className="dr-title">{c.title}</span>
      {c.detail && !isRemove && <span style={{ color: 'var(--ink-3)', fontSize: 12 }}>{c.detail}</span>}
      <span className="dr-extra">
        {c.cost != null && c.cost > 0 && <span>¥{c.cost}</span>}
        {c.dayIndex != null && <Tag style={{ marginInlineEnd: 0 }}>Day {c.dayIndex}</Tag>}
      </span>
    </div>
  );
}

/** Plan Diff：任何 AI 修改都不直接覆盖，先 Diff → 确认 → Commit */
export default function PlanDiff({ open, result, onConfirm, onCancel, loading }: Props) {
  const changes = result?.diff?.changes ?? [];
  const before = result?.diff?.budgetBefore ?? 0;
  const after = result?.diff?.budgetAfter ?? 0;
  const highRisk = result?.confirmation?.riskLevel === 'HIGH';

  return (
    <Modal
      open={open}
      title="🆚 方案确认 · 原方案 → 新方案"
      okText="确认修改"
      cancelText="取消"
      onOk={onConfirm}
      onCancel={onCancel}
      confirmLoading={loading}
      okButtonProps={{ danger: highRisk }}
      width={580}
    >
      {result?.confirmation?.summary && (
        <Alert
          type={highRisk ? 'warning' : 'info'}
          showIcon
          message={result.confirmation.summary}
          description={highRisk ? '本次调整会超出原预算，属于高风险操作，请确认后再提交。' : undefined}
          style={{ marginBottom: 14, marginTop: 4 }}
        />
      )}
      <div style={{ maxHeight: 320, overflowY: 'auto' }}>{changes.map((c, idx) => renderChange(c))}</div>
      <div className="diff-budget-box">
        <div>
          <div className="db-label">调整前</div>
          <div className="db-value" style={{ color: 'var(--ink-3)' }}>
            ¥{Number(before).toFixed(0)}
          </div>
        </div>
        <ArrowRightOutlined style={{ fontSize: 20, color: 'var(--primary)' }} />
        <div style={{ textAlign: 'right' }}>
          <div className="db-label">调整后（{(after - before >= 0 ? '+' : '') + Number(after - before).toFixed(0)}）</div>
          <div className="db-value" style={{ color: after > before ? 'var(--danger)' : 'var(--success)' }}>
            ¥{Number(after).toFixed(0)}
          </div>
        </div>
      </div>
    </Modal>
  );
}
