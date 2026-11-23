import { Button, Tag } from 'antd';
import { ArrowRightOutlined, CloseCircleOutlined, ReloadOutlined } from '@ant-design/icons';
import CitationList from '../CitationList';
import type { AgentResult } from '../../types/agent';
import './result-card.css';

/**
 * 结构化结果卡（体验方案阶段 C · §8.6）。
 *
 * 为什么不再只显示一段文字：Agent 的结论同时包含「做了什么 / 影响哪些安排 / 依据是什么 /
 * 有哪些没核实 / 下一步能点哪里」。纯文本把这些混成一段，用户既看不出影响范围，也不知道
 * 待确认和失败的区别。这里按状态聚合展示，并把可执行动作放在卡片里。
 *
 * 状态口径与 P0-07 一致：**只要有过程警告，即使后端返回 COMPLETED 也只展示「部分完成」**，
 * 不能给无条件绿色「已完成」。
 */

type Tone = 'ok' | 'warn' | 'bad' | 'muted';

const STATUS_META: Record<string, { label: string; tone: Tone; hint: string }> = {
  COMPLETED: { label: '已完成', tone: 'ok', hint: '本轮已正常收尾' },
  NEED_CONFIRMATION: {
    label: '待确认',
    tone: 'warn',
    hint: '方案尚未写入行程，需要你确认',
  },
  PARTIAL_SUCCESS: {
    label: '部分完成',
    tone: 'warn',
    hint: '有模块未完成或降级，结论可能不完整',
  },
  FAILED: { label: '失败', tone: 'bad', hint: '本轮没有完成，可按提示重试' },
  CANCELLED: { label: '已停止', tone: 'muted', hint: '你主动停止了本轮生成' },
};

const INTENT_LABELS: Record<string, string> = {
  PLAN_TRIP: '行程规划',
  PLAN_TRIP_NEED_INFO: '补充需求',
  MODIFY_ITINERARY: '行程调整',
  RECORD_EXPENSE: '记一笔消费',
  ADD_MEMO: '记备忘',
  QUERY_MEMO: '查备忘',
  ADD_SCHEDULE: '加日程',
  QUERY_SCHEDULE: '查日程',
  QUERY_KNOWLEDGE: '攻略查询',
  QUERY_BUDGET: '预算查询',
  CHITCHAT: '闲聊',
  CANCELLED: '已取消',
  ERROR: '异常',
  DEGRADE: '降级处理',
};

const CHANGE_LABELS: Record<string, string> = {
  ADD: '新增',
  REMOVE: '删除',
  MODIFY: '调整',
};

interface Props {
  result: AgentResult;
  /** 本轮 SSE 过程警告；与 result.warnings 合并去重 */
  warnings?: string[];
  /** 重试上一条请求（失败 / 主动停止时提供） */
  onRetry?: () => void;
  /** 取消待确认方案（服务端确认后才移除，P1-07） */
  onCancelPlan?: () => void;
  /** 打开本轮落地的行程 */
  onOpenTrip?: (tripId: number) => void;
}

function effectiveStatus(result: AgentResult, warnings: string[]): string {
  if (result.status === 'COMPLETED' && warnings.length > 0) return 'PARTIAL_SUCCESS';
  return result.status || 'COMPLETED';
}

/** 影响范围：Diff 条数 + 前三条标题 + 预算前后对比 */
function scopeLines(result: AgentResult): string[] {
  const lines: string[] = [];
  const changes = result.diff?.changes ?? [];
  if (changes.length > 0) {
    const titles = changes
      .slice(0, 3)
      .map((c) => {
        const label = CHANGE_LABELS[c.type] ?? c.type;
        return `${label}${c.title ? `「${c.title}」` : ''}`;
      })
      .join('、');
    const more = changes.length > 3 ? ` 等 ${changes.length} 项` : '';
    lines.push(`${titles}${more}`);
  }
  // 预算变化优先用 Diff 上的前后值（方案预览阶段的权威口径），其次用预算汇总
  const before = result.diff?.budgetBefore ?? result.budget?.previousTotal;
  const after = result.diff?.budgetAfter ?? result.budget?.total;
  if (typeof before === 'number' && typeof after === 'number' && before !== after) {
    lines.push(`预算 ¥${before.toFixed(0)} → ¥${after.toFixed(0)}`);
  } else if (typeof after === 'number' && !result.diff) {
    lines.push(`预算合计 ¥${after.toFixed(0)}`);
  }
  if (result.tripId) lines.push(`关联行程 #${result.tripId}`);
  return lines;
}

export default function ResultCard({
  result,
  warnings = [],
  onRetry,
  onCancelPlan,
  onOpenTrip,
}: Props) {
  const mergedWarnings = Array.from(
    new Set([...(result.warnings ?? []), ...warnings].filter(Boolean)),
  );
  const status = effectiveStatus(result, mergedWarnings);
  const meta = STATUS_META[status] ?? {
    label: status,
    tone: 'muted' as Tone,
    hint: '',
  };
  const scope = scopeLines(result);
  const issues = result.review?.issues ?? [];
  const intentLabel = result.intent ? (INTENT_LABELS[result.intent] ?? result.intent) : null;

  return (
    <section
      className={`result-card result-card--${meta.tone}`}
      data-testid="result-card"
      data-status={status}
      aria-label={`Agent 结果：${meta.label}`}
    >
      <header className="rc-head">
        <span className="rc-status" data-testid="result-status">
          {meta.label}
        </span>
        {intentLabel && <Tag bordered={false}>{intentLabel}</Tag>}
        {meta.hint && <span className="rc-hint">{meta.hint}</span>}
      </header>

      <p className="rc-message">{result.message || '（无返回内容）'}</p>

      {scope.length > 0 && (
        <div className="rc-section" data-testid="result-scope">
          <b>影响范围</b>
          <ul>
            {scope.map((line) => (
              <li key={line}>{line}</li>
            ))}
          </ul>
        </div>
      )}

      {issues.length > 0 && (
        <div className="rc-section" data-testid="result-issues">
          <b>方案审核</b>
          <ul>
            {issues.slice(0, 4).map((issue) => (
              <li key={`${issue.type}-${issue.message}`}>
                {issue.severity === 'HIGH' ? '高风险' : '需留意'} · {issue.message}
              </li>
            ))}
          </ul>
        </div>
      )}

      {mergedWarnings.length > 0 && (
        <div className="rc-section rc-warnings" data-testid="result-warnings">
          <b>待核实</b>
          <ul>
            {mergedWarnings.map((w) => (
              <li key={w}>{w}</li>
            ))}
          </ul>
        </div>
      )}

      <CitationList citations={result.citations} compact />

      <footer className="rc-actions">
        {status === 'NEED_CONFIRMATION' && onCancelPlan && (
          <Button size="small" icon={<CloseCircleOutlined />} onClick={onCancelPlan}>
            取消方案
          </Button>
        )}
        {(status === 'FAILED' || status === 'CANCELLED') && onRetry && (
          <Button size="small" icon={<ReloadOutlined />} onClick={onRetry}>
            重试
          </Button>
        )}
        {status !== 'NEED_CONFIRMATION' && result.tripId && onOpenTrip && (
          <Button
            size="small"
            type="primary"
            ghost
            icon={<ArrowRightOutlined />}
            onClick={() => onOpenTrip(result.tripId as number)}
          >
            打开行程
          </Button>
        )}
      </footer>
    </section>
  );
}
