import { useEffect, useState } from 'react';
import { App, Button, Drawer, Space, Tooltip } from 'antd';
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
    <div className="trip-page">
      <div className="trip-header">
        <Button type="text" icon={<ArrowLeftOutlined />} onClick={() => navigate('/')} />
        <div className="th-title">
          <b>{currentTrip?.name ?? '行程'}</b>
          <span className="th-meta">
            {currentTrip ? `${currentTrip.destination} · ${daysOf(currentTrip)} 天` : '加载中…'}
          </span>
        </div>
        <div className="budget-pill">
          <span className={`bp-value ${over ? 'over' : 'ok'}`}>¥{total.toFixed(0)}</span>
          <span className="bp-label">{over ? '已超出预算 ' : '预算 '}¥{budget ?? '-'}</span>
        </div>
        <Space>
          <Tooltip title="撤销上一次 AI 修改">
            <Button icon={<UndoOutlined />} onClick={onUndo} loading={undoing} />
          </Tooltip>
          <Button icon={<WalletOutlined />} onClick={() => setExpenseOpen(true)}>
            记账
          </Button>
          <Button type="primary" ghost icon={<BarChartOutlined />} onClick={() => setBudgetOpen(true)}>
            预算分析
          </Button>
        </Space>
      </div>

      <div className="trip-body">
        <div className="map-card">
          <div className="map-badge">
            <EnvironmentOutlined /> Day {selectedDay} · 智能地图
          </div>
          <MapView items={items} focus={lastFocus} focusDay={selectedDay} />
        </div>

        <div className="timeline-card">
          <Timeline trip={currentTrip} items={items} focus={lastFocus} />
        </div>

        <div className="chat-card">
          <div className="chat-head">
            <div className="ch-avatar">🤖</div>
            <div>
              <b>AI 旅行助手</b>
              <span>随时调整行程 · 支持语音输入</span>
            </div>
          </div>
          <ChatPanel tripId={tripId} placeholder="例如：明天下雨，把户外景点换掉" />
        </div>
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
    </div>
  );
}
