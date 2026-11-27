import { useState } from 'react';
import { Alert, Checkbox, Modal, Tag } from 'antd';
import {
  ArrowRightOutlined,
  CloseOutlined,
  DiffOutlined,
  EditOutlined,
  PlusOutlined,
  StopOutlined,
} from '@ant-design/icons';
import type { AgentResult, DiffChange } from '../../types/agent';
import CitationList from '../CitationList';

interface Props {
  open: boolean;
  result: AgentResult | null;
  /**
   * Batch 13：存在未解决 HIGH 问题时，用户必须先勾选「我已了解上述风险」，
   * 此时回调参数为 true（后端据此接受风险）；旧流程回调 false。
   */
  onConfirm: (acknowledgeIssues?: boolean) => void;
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
      <span className="dr-icon" aria-hidden="true">
        {isRemove ? <CloseOutlined /> : isModify ? <EditOutlined /> : <PlusOutlined />}
      </span>
      <span className="dr-title">{c.title}</span>
      {c.detail && !isRemove && (
        <span style={{ color: 'var(--ink-3)', fontSize: 12 }}>{c.detail}</span>
      )}
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
  const citations = result?.citations ?? [];

  // Batch 13：审核结论与「未解决高风险必须显式接受」的确认门槛
  const review = result?.review ?? null;
  const blocked = review?.blocked === true;
  const issues = review?.issues ?? [];
  const repairs = review?.repairs ?? [];
  const attempts = review?.attempts ?? 0;
  // requiresAcknowledgement 是 review.blocked 的派生字段；旧后端两者都没有 → false
  const ackRequired = result?.confirmation?.requiresAcknowledgement ?? blocked;
  const [ack, setAck] = useState(false);
  // 每次弹窗打开/换成另一个方案都重新要求勾选：在渲染期收敛（值变化时调整 state），
  // 避免 effect 内同步 setState 多跑一轮渲染。
  const [ackSeed, setAckSeed] = useState({ open, planId: result?.planId });
  if (ackSeed.open !== open || ackSeed.planId !== result?.planId) {
    setAckSeed({ open, planId: result?.planId });
    setAck(false);
  }

  const handleOk = () => {
    if (ackRequired && !ack) return; // 按钮已 disabled，这里只做兜底
    onConfirm(ackRequired ? ack : false);
  };

  return (
    <Modal
      open={open}
      title={
        <span>
          <DiffOutlined aria-hidden="true" /> 方案确认 · 原方案 → 新方案
        </span>
      }
      okText="确认修改"
      cancelText="取消"
      onOk={handleOk}
      onCancel={onCancel}
      confirmLoading={loading}
      // 确认按钮的 data-testid 交给 antd Button：它会透传到真实 <button>，e2e 直接点
      // 未勾选「接受风险」时按钮 disabled：高风险必须显式确认，不能一键提交
      okButtonProps={{
        danger: highRisk,
        'data-testid': 'plan-diff-confirm',
        disabled: ackRequired && !ack,
      }}
      width={580}
    >
      {/* data-testid 必须放在有实际尺寸的内容容器上：antd 的 .ant-modal-root 只是零尺寸包裹层，
          零尺寸元素会被 Playwright 判为 hidden，把 testid 标在 Modal 上会让 e2e 永远不可见 */}
      <div data-testid="plan-diff-dialog">
        {blocked ? (
          <Alert
            type="error"
            showIcon
            message="存在未解决的问题，需明确接受风险后才能提交"
            description={result?.confirmation?.summary || undefined}
            style={{ marginBottom: 14, marginTop: 4 }}
          />
        ) : (
          result?.confirmation?.summary && (
            <Alert
              type={highRisk ? 'warning' : 'info'}
              showIcon
              message={result.confirmation.summary}
              description={
                highRisk ? '本次调整会超出原预算，属于高风险操作，请确认后再提交。' : undefined
              }
              style={{ marginBottom: 14, marginTop: 4 }}
            />
          )
        )}
        {review && (
          <div className="plan-review" data-testid="plan-review">
            <div className="plan-review-head">
              <b>审核结论</b>
              <span
                className={`pr-badge ${blocked ? 'blocked' : review.passed ? 'passed' : 'warn'}`}
              >
                {blocked ? '存在未解决高风险' : review.passed ? '审核通过' : '审核未通过'}
              </span>
            </div>
            {issues.length > 0 && (
              <div className="plan-review-issues" data-testid="plan-review-issues">
                {issues.map((issue, i) => (
                  <div
                    key={`${issue.type}-${i}`}
                    className={`plan-warning-row ${issue.severity === 'HIGH' ? 'high' : 'medium'}`}
                  >
                    <span className="pw-icon">
                      {issue.severity === 'HIGH' ? <StopOutlined aria-hidden="true" /> : '⚠'}
                    </span>
                    <span className="pw-text">{issue.message}</span>
                  </div>
                ))}
              </div>
            )}
            {repairs.length > 0 && (
              <div className="plan-review-repairs" data-testid="plan-review-repairs">
                已自动重规划 {attempts} 次：{repairs.join('；')}
              </div>
            )}
          </div>
        )}
        {/* 有结构化审核结论时问题已在上面逐条展示；warnings 是旧契约的同一批文案，不再重复渲染 */}
        {warnings.length > 0 && !review && (
          <div className="plan-warnings" data-testid="plan-diff-warnings">
            {warnings.map((w, i) => (
              <div key={i} className="plan-warning-row">
                <span className="pw-icon">⚠</span>
                <span className="pw-text">{w}</span>
              </div>
            ))}
          </div>
        )}
        {/* 知识依据：只在后端给出 citations 时展示；空数组不渲染，不伪造来源 */}
        <CitationList citations={citations} />
        {contentHash && (
          <div
            className="plan-fingerprint"
            data-testid="plan-diff-fingerprint"
            title={`方案内容 SHA-256：${contentHash}`}
          >
            <span className="pf-label">内容指纹</span>
            <span className="pf-value">{shortHash(contentHash)}</span>
            <span className="pf-hint">确认将绑定此方案内容</span>
          </div>
        )}
        <div style={{ maxHeight: 320, overflowY: 'auto' }}>
          {changes.map((c, idx) => renderChange(c, idx))}
        </div>
        <div className="diff-budget-box">
          <div>
            <div className="db-label">调整前</div>
            <div className="db-value" style={{ color: 'var(--ink-3)' }}>
              ¥{Number(before).toFixed(0)}
            </div>
          </div>
          <ArrowRightOutlined style={{ fontSize: 20, color: 'var(--primary)' }} />
          <div style={{ textAlign: 'right' }}>
            <div className="db-label">
              调整后（{(after - before >= 0 ? '+' : '') + Number(after - before).toFixed(0)}）
            </div>
            <div
              className="db-value"
              style={{ color: after > before ? 'var(--danger)' : 'var(--success)' }}
            >
              ¥{Number(after).toFixed(0)}
            </div>
          </div>
        </div>
        {ackRequired && (
          <div className="plan-ack">
            <Checkbox
              data-testid="plan-diff-ack"
              checked={ack}
              onChange={(e) => setAck(e.target.checked)}
            >
              我已了解上述风险，仍然提交
            </Checkbox>
          </div>
        )}
      </div>
    </Modal>
  );
}
