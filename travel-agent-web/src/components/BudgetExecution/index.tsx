import type { BudgetExecution, DailyBudget } from '../../types/expense';

/** 金额展示：null/undefined → 占位；负数的符号放在 ¥ 之前（-¥50 而不是 ¥-50） */
const money = (value: number | null | undefined) =>
  value == null ? '—' : `${value < 0 ? '-' : ''}¥${Math.round(Math.abs(value)).toLocaleString()}`;

/** 预算执行摘要：计划成本 / 预算 / 实际支出 / 偏差 / 使用率，并给出分类与按天对账 */
export default function BudgetExecutionPanel({ data }: { data: BudgetExecution | null }) {
  if (!data) {
    return null;
  }
  const ratio =
    data.budget && data.budget > 0 ? Math.min(Math.max(data.actual / data.budget, 0), 1) : 0;
  const over = data.overBudget;
  // executionRate 是 actual / budget 的比例（后端保留 4 位小数，例如 0.8523 = 85%），无预算时为 null
  const ratePercent = data.executionRate == null ? null : data.executionRate * 100;
  const rateOver = ratePercent != null && ratePercent > 100;
  // daily 在契约里恒返回；旧后端可能缺失，这里按空数组兜底，避免直接 .length 崩掉整个面板
  const daily: DailyBudget[] = Array.isArray(data.daily) ? data.daily : [];

  return (
    <div className="budget-exec">
      <div className="be-grid">
        <div className="be-item">
          <span>预算</span>
          <b>{money(data.budget)}</b>
        </div>
        <div className="be-item">
          <span>计划成本</span>
          <b>{money(data.planned)}</b>
        </div>
        <div className="be-item">
          <span>实际支出</span>
          <b className={over ? 'over' : ''}>{money(data.actual)}</b>
        </div>
        <div className="be-item">
          <span>较计划</span>
          <b className={data.variance > 0 ? 'over' : 'ok'}>
            {data.variance > 0 ? '+' : ''}
            {money(data.variance)}
          </b>
        </div>
        <div className="be-item">
          <span>预算使用率</span>
          <b className={rateOver ? 'over' : ''}>
            {ratePercent == null ? '—' : `${Math.round(ratePercent)}%`}
          </b>
        </div>
      </div>

      {data.budget != null && data.budget > 0 && (
        <>
          <div className={`be-bar ${over ? 'over' : ''}`}>
            <i style={{ width: `${Math.round(ratio * 100)}%` }} />
          </div>
          <div className="be-note">
            {over
              ? `已超出预算 ${money(Math.abs(data.remaining ?? 0))}`
              : `剩余预算 ${money(data.remaining)}`}
            {data.planOverBudget && <span className="be-warn"> · 计划成本本身已超预算</span>}
          </div>
        </>
      )}

      {data.categories.length > 0 && (
        <div className="be-cats">
          {data.categories.map((c) => (
            <div className="be-cat" key={c.category}>
              <span className="be-cat-name">{c.category}</span>
              <span className="be-cat-num">计划 {money(c.planned)}</span>
              <span className={`be-cat-num ${c.actual > c.planned ? 'over' : ''}`}>
                实际 {money(c.actual)}
              </span>
            </div>
          ))}
        </div>
      )}

      {daily.length > 0 && (
        <div className="be-cats">
          {daily.map((d) => (
            <div className="be-cat" key={d.day}>
              <span className="be-cat-name">{d.day === 0 ? '行程外' : `Day ${d.day}`}</span>
              <span className="be-cat-num">{d.day === 0 ? '—' : `计划 ${money(d.planned)}`}</span>
              <span className="be-cat-num">实际 {money(d.actual)}</span>
              <span className={`be-cat-num ${d.variance > 0 ? 'over' : 'ok'}`}>
                {d.variance > 0 ? '+' : ''}
                {money(d.variance)}
              </span>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
