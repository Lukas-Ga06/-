import { WarningOutlined } from '@ant-design/icons';
import type { RequirementRow } from '../planForm';
import type { ParsedPlanPrompt } from '../parsePrompt';

interface StepConfirmProps {
  /** 结构化需求：第 3 步与右侧摘要共用同一份口径 */
  requirementRows: RequirementRow[];
  parsedPrompt: ParsedPlanPrompt | null;
  /** 待核实项：解析置信度不足的字段 + 方案会替用户做的假设 */
  pendingItems: string[];
  /** 第 1、2 步的硬门禁文案；有值时说明恢复出来的需求不完整 */
  blockingIssue: string | null;
}

/**
 * 第 3 步：只读核对。把要提交的结构化需求、冲突与待核实项摆在一起，
 * 用户在这一步只需要确认，不再填写任何字段。
 */
export function PlanStepConfirm({
  requirementRows,
  parsedPrompt,
  pendingItems,
  blockingIssue,
}: StepConfirmProps) {
  return (
    <div className="plan3-step-body">
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
  );
}

interface AsideProps {
  /** 只展示「已填内容 + 必填项」：把整张表都摆上去会变成第二份表单 */
  asideRows: RequirementRow[];
  /** 还没填的必填项标签，用于底部一行「还差：…」 */
  missingRequired: string[];
}

/**
 * 桌面端右侧常驻需求摘要：随表单实时更新，用户不用切到第 3 步就能核对。
 * 移动端不渲染（宽度不够，摘要会挤掉表单）。
 */
export function RequirementAside({ asideRows, missingRequired }: AsideProps) {
  return (
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
  );
}
