import { useEffect, useState } from 'react';
import { App, Button, Drawer, Tooltip } from 'antd';
import {
  ArrowLeftOutlined,
  BarChartOutlined,
  EnvironmentOutlined,
  UndoOutlined,
  WalletOutlined,
} from '@ant-design/icons';
import { useNavigate, useParams } from 'react-router-dom';
import { useTripStore } from '../../store/tripStore';
import { useAgentStore } from '../../store/agentStore';
import { useUIStore } from '../../store/uiStore';
import { undoTrip } from '../../api/agent';
import { daysOf, totalCost } from '../../types/trip';
import MapView from '../../components/MapView';
import Timeline from '../../components/Timeline';
import ChatPanel from '../../components/ChatPanel';
import PlanDiff from '../../components/PlanDiff';
import BudgetChart from '../../components/BudgetChart';
import ExpensePanel from '../../components/ExpensePanel';
import AppShell from '../../components/AppShell';

/** 行程主界面：工作台式布局 —— 地图 + AI 助手 + 时间轴 + 预算 */
export default function TripPage() {
  const { id } = useParams();
  const tripId = Number(id);
  const navigate = useNavigate();
  const { message } = App.useApp();

  const currentTrip = useTripStore((s) => s.currentTrip);
  const items = useTripStore((s) => s.items);
  const loadTrip = useTripStore((s) => s.loadTrip);
  const loadItinerary = useTripStore((s) => s.loadItinerary);
  const clearCurrent = useTripStore((s) => s.clearCurrent);

  const lastFocus = useAgentStore((s) => s.lastFocus);
  const pendingConfirm = useAgentStore((s) => s.pendingConfirm);
  const confirm = useAgentStore((s) => s.confirm);
  const cancel = useAgentStore((s) => s.cancel);
  const streaming = useAgentStore((s) => s.streaming);
  const reset = useAgentStore((s) => s.reset);

  const selectedDay = useUIStore((s) => s.selectedDay);
  const setSelectedDay = useUIStore((s) => s.setSelectedDay);

  const [budgetOpen, setBudgetOpen] = useState(false);
  const [expenseOpen, setExpenseOpen] = useState(false);
  const [undoing, setUndoing] = useState(false);

  useEffect(() => {
    reset();
    clearCurrent();
    setSelectedDay(1);
    if (tripId) {
      loadTrip(tripId).catch((e) => message.error(e.message));
      loadItinerary(tripId).catch((e) => message.error(e.message));
    }
  }, [tripId]);

  // Agent Focus：同步选中的 Day
  useEffect(() => {
    if (lastFocus?.day) setSelectedDay(lastFocus.day);
  }, [lastFocus]);

  const total = totalCost(items);
  const budget = currentTrip?.budget ?? null;
  const over = budget != null && total > budget;

  const onUndo = async () => {
    setUndoing(true);
    try {
      const result = await undoTrip(tripId);
      await loadItinerary(tripId);
      message.success(result.message || '已撤销');
    } catch (e: any) {
      message.error(e.message);
    } finally {
      setUndoing(false);
    }
  };

  const onConfirm = async () => {
    const result = await confirm();
    if (result?.tripId) {
      await loadItinerary(result.tripId).catch(() => undefined);
    }
  };

  return (
    <AppShell active="travel">
      <div className="dash-wrap">
        <div className="dash-greeting">
          <div className="dg-left">
            <Button type="text" icon={<ArrowLeftOutlined />} onClick={() => navigate('/travel')} />
            <div>
              <h1>{currentTrip?.name ?? '行程'}</h1>
              <div className="dg-date">
                {currentTrip
                  ? `${currentTrip.destination} · ${daysOf(currentTrip)} 天 · ${items.length} 个安排`
                  : '加载中…'}
              </div>
            </div>
          </div>
          <div className="page-actions">
            <span className={`chip ${over ? 'chip-warn' : 'chip-ok'}`}>
              已花 ¥{total.toFixed(0)} / 预算 ¥{budget ?? '-'}
            </span>
            <Tooltip title="撤销上一次 AI 修改">
              <Button icon={<UndoOutlined />} onClick={onUndo} loading={undoing}>
                撤销
              </Button>
            </Tooltip>
            <Button icon={<WalletOutlined />} onClick={() => setExpenseOpen(true)}>
              记账
            </Button>
            <Button type="primary" ghost icon={<BarChartOutlined />} onClick={() => setBudgetOpen(true)}>
              预算分析
            </Button>
          </div>
        </div>

        <div className="trip-grid">
          <section className="dash-card trip-map-card">
            <div className="dc-head">
              <b>地图</b>
              <span className="mini-tag">
                <EnvironmentOutlined /> Day {selectedDay}
              </span>
            </div>
            <div className="trip-map-body">
              <MapView items={items} focus={lastFocus} focusDay={selectedDay} />
            </div>
          </section>

          <aside className="dash-card trip-chat-card">
            <div className="dc-head">
              <b>AI 助手</b>
              <span className="mini-tag">随时调整行程</span>
            </div>
            <div className="trip-chat-body">
              <ChatPanel tripId={tripId} placeholder="例如：明天下雨，把户外景点换掉" />
            </div>
          </aside>
        </div>

        <section className="dash-card trip-timeline-card">
          <Timeline trip={currentTrip} items={items} focus={lastFocus} />
        </section>
      </div>

      <Drawer title="预算分析" open={budgetOpen} onClose={() => setBudgetOpen(false)} width={560}>
        <BudgetChart items={items} budget={budget} />
      </Drawer>
      <Drawer
        title="行程记账 · 实际支出"
        open={expenseOpen}
        onClose={() => setExpenseOpen(false)}
        width={480}
        styles={{ body: { paddingTop: 12 } }}
      >
        <ExpensePanel tripId={tripId} budget={budget} planned={total} />
      </Drawer>
      <PlanDiff open={!!pendingConfirm} result={pendingConfirm} onConfirm={onConfirm} onCancel={() => cancel()} loading={streaming} />
    </AppShell>
  );
}
