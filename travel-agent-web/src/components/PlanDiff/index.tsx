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

/** SHA-256 内容指纹的短展示：前 8 + 后 8 位（64 位十六进制足够肉眼比对） */
function shortHash(hash: string): string {
  return hash.length > 16 ? `${hash.slice(0, 8)}…${hash.slice(-8)}` : hash;
}

function renderChange(c: DiffChange, key: number) {
  const isRemove = c.type === 'REMOVE';
  const isModify = c.type === 'MODIFY';
  return (
    <div key={key} className={`diff-row ${isRemove ? 'remove' : 'add'}`}>
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
  const warnings = result?.warnings ?? [];
  const contentHash = result?.confirmation?.contentHash;

  return (
    <Modal
      open={open}
      title="🆚 方案确认 · 原方案 → 新方案"
      okText="确认修改"
      cancelText="取消"
      onOk={onConfirm}
      onCancel={onCancel}
      confirmLoading={loading}
      // 确认按钮的 data-testid 交给 antd Button：它会透传到真实 <button>，e2e 直接点
      okButtonProps={{ danger: highRisk, 'data-testid': 'plan-diff-confirm' }}
      width={580}
    >
      {/* data-testid 必须放在有实际尺寸的内容容器上：antd 的 .ant-modal-root 只是零尺寸包裹层，
          零尺寸元素会被 Playwright 判为 hidden，把 testid 标在 Modal 上会让 e2e 永远不可见 */}
      <div data-testid="plan-diff-dialog">
        {result?.confirmation?.summary && (
          <Alert
            type={highRisk ? 'warning' : 'info'}
            showIcon
            message={result.confirmation.summary}
            description={highRisk ? '本次调整会超出原预算，属于高风险操作，请确认后再提交。' : undefined}
            style={{ marginBottom: 14, marginTop: 4 }}
          />
        )}
        {warnings.length > 0 && (
          <div className="plan-warnings" data-testid="plan-diff-warnings">
            {warnings.map((w, i) => (
              <div key={i} className="plan-warning-row">
                <span className="pw-icon">⚠</span>
                <span className="pw-text">{w}</span>
              </div>
            ))}
          </div>
        )}
        {contentHash && (
          <div className="plan-fingerprint" data-testid="plan-diff-fingerprint" title={`方案内容 SHA-256：${contentHash}`}>
            <span className="pf-label">内容指纹</span>
            <span className="pf-value">{shortHash(contentHash)}</span>
            <span className="pf-hint">确认将绑定此方案内容</span>
          </div>
        )}
        <div style={{ maxHeight: 320, overflowY: 'auto' }}>{changes.map((c, idx) => renderChange(c, idx))}</div>
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
      </div>
    </Modal>
  );
}