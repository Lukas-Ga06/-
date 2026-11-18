import { useEffect, useState } from 'react';
import { App, Button, Drawer, Tooltip } from 'antd';
import {
  ArrowLeftOutlined,
  BarChartOutlined,
  BookOutlined,
  EnvironmentOutlined,
  UndoOutlined,
  WalletOutlined,
} from '@ant-design/icons';
import { useNavigate, useParams } from 'react-router-dom';
import { useTripStore } from '../../store/tripStore';
import { useAgentStore } from '../../store/agentStore';
import { useUIStore } from '../../store/uiStore';
import { undoTrip } from '../../api/agent';
import { getBudgetExecution } from '../../api/trip';
import type { BudgetExecution } from '../../types/expense';
import { daysOf, totalCost, tripPhaseLabel } from '../../types/trip';
import MapView from '../../components/MapView';
import Timeline from '../../components/Timeline';
import ChatPanel from '../../components/ChatPanel';
import PlanDiff from '../../components/PlanDiff';
import BudgetChart from '../../components/BudgetChart';
import BudgetExecutionPanel from '../../components/BudgetExecution';
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
  const routeLegs = useTripStore((s) => s.routeLegs);
  const loadTrip = useTripStore((s) => s.loadTrip);
  const loadItinerary = useTripStore((s) => s.loadItinerary);
  const loadRouteLegs = useTripStore((s) => s.loadRouteLegs);
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
  const [budgetExecution, setBudgetExecution] = useState<BudgetExecution | null>(null);
  const [expenseOpen, setExpenseOpen] = useState(false);
  const [undoing, setUndoing] = useState(false);

  useEffect(() => {
    reset();
    clearCurrent();
    setSelectedDay(1);
    if (tripId) {
      loadTrip(tripId).catch((e) => message.error(e.message));
      // 转场段依赖行程内容：itinerary 到位后（无论成功失败）都再拉一次，失败静默降级
      loadItinerary(tripId)
        .catch((e) => message.error(e.message))
        .finally(() => {
          loadRouteLegs(tripId);
        });
    }
  }, [tripId]);

  // Agent Focus：同步选中的 Day
  useEffect(() => {
    if (lastFocus?.day) setSelectedDay(lastFocus.day);
  }, [lastFocus]);

  // 打开预算抽屉时拉取统一口径的预算执行（计划 / 预算 / 实际 / 偏差）
  useEffect(() => {
    if (!budgetOpen || !tripId) return;
    getBudgetExecution(tripId)
      .then(setBudgetExecution)
      .catch(() => setBudgetExecution(null));
  }, [budgetOpen, tripId, items.length]);

  const total = totalCost(items);
  const budget = currentTrip?.budget ?? null;
  const over = budget != null && total > budget;

  const onUndo = async () => {
    setUndoing(true);
    try {
      const result = await undoTrip(tripId);
      await loadItinerary(tripId);
      await loadRouteLegs(tripId);
      message.success(result.message || '已撤销');
    } catch (e: any) {
      message.error(e.message);
    } finally {
      setUndoing(false);
    }
  };

  // acknowledgeIssues：用户在确认弹窗里勾选了「接受未解决风险」（Batch 13）
  const onConfirm = async (acknowledgeIssues?: boolean) => {
    const result = await confirm(acknowledgeIssues);
    if (result?.tripId) {
      await loadItinerary(result.tripId).catch(() => undefined);
      // itinerary 变了转场段也会变，必须重拉，否则时间线/地图仍显示旧路径
      await loadRouteLegs(result.tripId);
    }
  };

  // 路书导出走同源 GET：登录态在 httpOnly Cookie 里，顶层导航会自动携带（经 vite 代理到后端）
  const onExportGuidebook = () => {
    if (!tripId || Number.isNaN(tripId)) return;
    window.open(`/api/trips/${tripId}/guidebook.pdf`, '_blank');
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
                  ? `${currentTrip.destination} · ${daysOf(currentTrip)} 天 · ${tripPhaseLabel(currentTrip)} · ${items.length} 个安排`
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
            <Tooltip title="导出可打印的路书 PDF">
              <Button icon={<BookOutlined />} onClick={onExportGuidebook}>
                路书
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
              <MapView items={items} focus={lastFocus} focusDay={selectedDay} routeLegs={routeLegs} />
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
          <Timeline trip={currentTrip} items={items} focus={lastFocus} routeLegs={routeLegs} />
        </section>
      </div>

      <Drawer title="预算分析" open={budgetOpen} onClose={() => setBudgetOpen(false)} width={560}>
        <BudgetExecutionPanel data={budgetExecution} />
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
