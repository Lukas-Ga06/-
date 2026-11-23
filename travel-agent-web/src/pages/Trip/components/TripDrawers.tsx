import { Drawer } from 'antd';
import BudgetChart from '../../../components/BudgetChart';
import BudgetExecutionPanel from '../../../components/BudgetExecution';
import ExpensePanel from '../../../components/ExpensePanel';
import type { BudgetExecution } from '../../../types/expense';
import type { ItineraryItem } from '../../../types/trip';

interface Props {
  tripId: number;
  /** 预算分析抽屉：计划 / 预算 / 实际 / 偏差的统一口径 */
  budgetOpen: boolean;
  onBudgetClose: () => void;
  budgetExecution: BudgetExecution | null;
  items: ItineraryItem[];
  budget: number | null;
  /** 行程记账抽屉：实际支出录入 */
  expenseOpen: boolean;
  onExpenseClose: () => void;
  /** 已花金额（计划口径），记账页用来对比 */
  total: number;
}

/**
 * 行程页的两个抽屉浮层：预算分析与实际支出记账。
 * 只负责展示，数据加载（预算执行）与开关状态都在页面里。
 */
export default function TripDrawers({
  tripId,
  budgetOpen,
  onBudgetClose,
  budgetExecution,
  items,
  budget,
  expenseOpen,
  onExpenseClose,
  total,
}: Props) {
  return (
    <>
      <Drawer title="预算分析" open={budgetOpen} onClose={onBudgetClose} width={560}>
        <BudgetExecutionPanel data={budgetExecution} />
        <BudgetChart items={items} budget={budget} />
      </Drawer>
      <Drawer
        title="行程记账 · 实际支出"
        open={expenseOpen}
        onClose={onExpenseClose}
        width={480}
        styles={{ body: { paddingTop: 12 } }}
      >
        <ExpensePanel tripId={tripId} budget={budget} planned={total} />
      </Drawer>
    </>
  );
}
