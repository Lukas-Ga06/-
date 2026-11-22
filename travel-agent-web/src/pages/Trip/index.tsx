import { useEffect, useMemo, useRef, useState } from 'react';
import { App, Button, Drawer, Dropdown, Segmented } from 'antd';
import {
  ArrowLeftOutlined,
  BarChartOutlined,
  BookOutlined,
  CloudSyncOutlined,
  EnvironmentOutlined,
  MoreOutlined,
  ReadOutlined,
  UndoOutlined,
  WalletOutlined,
} from '@ant-design/icons';
import { useNavigate, useParams } from 'react-router-dom';
import { useTripStore } from '../../store/tripStore';
import { useAgentStore } from '../../store/agentStore';
import { useUIStore } from '../../store/uiStore';
import { useStatusQueueStore } from '../../store/statusQueueStore';
import { useIsMobile } from '../../hooks/useMediaQuery';
import { useAgentScope } from '../../hooks/useAgentScope';
import { useOnlineStatus } from '../../hooks/useOnlineStatus';
import { undoTrip } from '../../api/agent';
import { getBudgetExecution, updateItemStatus } from '../../api/trip';
import type { BudgetExecution } from '../../types/expense';
import { daysOf, totalCost, tripPhaseLabel, type ItineraryStatus } from '../../types/trip';
import { overlayStatuses, pendingFor, pendingIds } from '../../lib/statusQueue';
import { conflictNotice, isTripConflict } from '../../lib/tripConflict';
import { nextFocusAfter, scrollToItem } from '../../lib/tripFocus';
import MapView from '../../components/MapView';
import Timeline from '../../components/Timeline';
import ChatPanel from '../../components/ChatPanel';
import PlanDiff from '../../components/PlanDiff';
import BudgetChart from '../../components/BudgetChart';
import BudgetExecutionPanel from '../../components/BudgetExecution';
import ExpensePanel from '../../components/ExpensePanel';
import AppShell from '../../components/AppShell';
import UndoBar from '../../components/UndoBar';
import './trip.css';
import './trip-flow.css';

/** 移动端分段视图：默认「行程」，地图与 AI 是辅助视图（不能压在时间轴之前） */
type MobileView = 'itinerary' | 'map' | 'ai';

/** 撤销条上的动作动词：说清「刚刚发生了什么」，而不是只给一个状态名 */
const STATUS_VERB: Record<ItineraryStatus, string> = {
  PENDING: '已重置',
  IN_PROGRESS: '已开始',
  COMPLETED: '已完成',
  SKIPPED: '已跳过',
};

/**
 * 网络层失败（离线 / 超时 / DNS）与业务拒绝（4xx/5xx 有响应）必须分开：
 * 前者要进待同步队列等联网重放，后者要立刻把服务端的理由告诉用户。
 */
function isNetworkFailure(error: unknown): boolean {
  const candidate = error as { status?: unknown; response?: unknown; code?: unknown } | null;
  if (candidate?.response) return false;
  if (typeof candidate?.status === 'number') return false;
  return true;
}

/** 一次状态变更的撤销窗口（§7.3 第 3 条） */
interface UndoState {
  token: number;
  itemId: number;
  title: string;
  previous: ItineraryStatus;
  next: ItineraryStatus;
  message: string;
  detail: string;
}

/** 行程主界面：工作台式布局 —— 地图 + AI 助手 + 时间轴 + 预算 */
export default function TripPage() {
  const { id } = useParams();
  const tripId = Number(id);
  // 会话隔离（阶段 C）：每个行程详情绑定独立会话作用域
  useAgentScope(`trip:${tripId}`);
  const navigate = useNavigate();
  const { message } = App.useApp();

  const currentTrip = useTripStore((s) => s.currentTrip);
  const serverItems = useTripStore((s) => s.items);
  const routeLegs = useTripStore((s) => s.routeLegs);
  const loadTrip = useTripStore((s) => s.loadTrip);
  const loadItinerary = useTripStore((s) => s.loadItinerary);
  const loadRouteLegs = useTripStore((s) => s.loadRouteLegs);
  const syncTrip = useTripStore((s) => s.syncTrip);
  const clearCurrent = useTripStore((s) => s.clearCurrent);

  // 离线待同步队列（§7.3 第 4 条）：队列条目叠在服务端数据上，
  // 未确认的状态立刻可见，但带「待同步」标记，和服务端已确认的状态严格区分。
  const pendingEntries = useStatusQueueStore((s) => s.entries);
  const enqueueStatus = useStatusQueueStore((s) => s.enqueue);
  const dropPending = useStatusQueueStore((s) => s.drop);
  const online = useOnlineStatus();
  const items = useMemo(
    () => overlayStatuses(serverItems, pendingEntries),
    [serverItems, pendingEntries],
  );
  const pendingSyncIds = useMemo(
    () => pendingIds(pendingEntries, tripId),
    [pendingEntries, tripId],
  );

  const lastFocus = useAgentStore((s) => s.lastFocus);
  const pendingConfirm = useAgentStore((s) => s.pendingConfirm);
  const confirm = useAgentStore((s) => s.confirm);
  const cancel = useAgentStore((s) => s.cancel);
  const streaming = useAgentStore((s) => s.streaming);

  const selectedDay = useUIStore((s) => s.selectedDay);
  const setSelectedDay = useUIStore((s) => s.setSelectedDay);

  const [budgetOpen, setBudgetOpen] = useState(false);
  const [budgetExecution, setBudgetExecution] = useState<BudgetExecution | null>(null);
  const [expenseOpen, setExpenseOpen] = useState(false);
  const [undoing, setUndoing] = useState(false);
  /** 正在提交状态变更的行程项 id（P1 旅行闭环） */
  const [statusBusy, setStatusBusy] = useState<number | null>(null);
  /** 短时撤销窗口；null = 没有可撤销的操作 */
  const [undo, setUndo] = useState<UndoState | null>(null);
  /** 撤销窗口的序号：同一毫秒内连续两次操作也要各自重新计时 */
  const undoSeq = useRef(0);
  /** 移动端分段视图；桌面端只用 sideView 切换右侧面板 */
  const isMobile = useIsMobile();
  const [mobileView, setMobileView] = useState<MobileView>('itinerary');
  const [sideView, setSideView] = useState<'map' | 'ai'>('map');

  useEffect(() => {
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

  /**
   * 回到页面时对版本（阶段 D）：别的标签页/设备改过同一行程就提示并刷新。
   * 只在 tab 重新可见时发一次请求，不轮询；3s 内的重复可见事件合并，
   * 避免用户快速切换窗口时连发请求。网络失败静默忽略——这是增强，不是主流程。
   */
  const lastVersionCheck = useRef(0);
  useEffect(() => {
    if (!tripId || typeof document === 'undefined') return;
    const checkVersion = () => {
      if (document.visibilityState !== 'visible') return;
      if (!useTripStore.getState().currentTrip) return;
      const now = Date.now();
      if (now - lastVersionCheck.current < 3000) return;
      lastVersionCheck.current = now;
      void (async () => {
        try {
          const changed = await syncTrip(tripId);
          if (!changed) return;
          message.warning(conflictNotice());
          await loadItinerary(tripId).catch(() => undefined);
          loadRouteLegs(tripId);
        } catch {
          // 弱网下版本探测失败很常见：不打扰用户，下次可见时再试
        }
      })();
    };
    document.addEventListener('visibilitychange', checkVersion);
    return () => document.removeEventListener('visibilitychange', checkVersion);
  }, [tripId, syncTrip, loadItinerary, loadRouteLegs, message]);

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
  /** 行中阶段：一级动作给「记账」，行前给「预算分析」（P1-05 按阶段收敛页头） */
  const ongoing = currentTrip?.phase === 'ONGOING';

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

  /** 状态变更后的服务端数据重拉：行程项与转场段必须同时刷新，否则时间线/地图仍是旧路径 */
  const refreshItinerary = async () => {
    if (!tripId) return;
    await loadItinerary(tripId);
    await loadRouteLegs(tripId);
  };

  /**
   * 冲突（409）后的统一收尾（阶段 D）：提示「此行程已在其他位置更新」，
   * 然后把行程、时间轴、转场段全部换成服务端最新值 —— 本地这份前提已经失效，
   * 不刷新就继续操作只会重复失败。刷新失败不吞提示：用户至少知道该手动重载。
   */
  const onTripConflict = async () => {
    message.warning(conflictNotice());
    await Promise.allSettled([syncTrip(tripId), loadItinerary(tripId), loadRouteLegs(tripId)]);
  };

  /**
   * 状态变更落地后的反馈（§7.3 第 3 条）：
   * 自动滚到下一项 + 开一个短时撤销窗口。
   * 目标项用「叠加了待同步状态」的最新数据算 —— 离线记录的那一条同样会推进「下一项」。
   */
  const announceChange = (
    itemId: number,
    next: ItineraryStatus,
    previous: ItineraryStatus,
    title: string,
    queued: boolean,
  ) => {
    const fresh = overlayStatuses(
      useTripStore.getState().items,
      useStatusQueueStore.getState().entries,
    );
    const focus = nextFocusAfter(
      fresh,
      useTripStore.getState().currentTrip,
      useUIStore.getState().selectedDay,
      itemId,
    );
    if (focus.kind === 'move' && focus.item.id != null) {
      setSelectedDay(focus.item.dayIndex);
      // 切 Day 会重渲染，等下一帧再滚
      scrollToItem(focus.item.id, 180);
    }
    const detail =
      focus.kind === 'move'
        ? `已滚到下一项：${focus.item.title}`
        : focus.kind === 'stay'
          ? '这一项仍是下一项'
          : '全部安排都处理完了';
    setUndo({
      token: ++undoSeq.current,
      itemId,
      title,
      previous,
      next,
      message: queued
        ? `「${title}」${STATUS_VERB[next]}，待同步`
        : `${STATUS_VERB[next]}「${title}」`,
      detail,
    });
  };

  /**
   * 行程项状态推进（P1）：后端会在 COMPLETED 时写 visitedAt 并追加旅行事件，
   * 状态变化进撤销链。提交后重拉行程与转场段，保证当天时间线立即反映真实状态。
   *
   * 离线（或请求根本发不出去）时不再直接报错：本地记录 + 入待同步队列，
   * 由 OfflineBanner 在恢复在线后逐项重放（§7.3 第 4 条 / §10.3）。
   */
  const onItemStatusChange = async (itemId: number, next: ItineraryStatus) => {
    if (statusBusy != null || !tripId) return;
    const current = items.find((i) => i.id === itemId);
    const previous = (current?.status ?? 'PENDING') as ItineraryStatus;
    const title = current?.title ?? '';

    if (!online) {
      enqueueStatus({ tripId, itemId, status: next, previousStatus: previous, title });
      announceChange(itemId, next, previous, title, true);
      return;
    }

    setStatusBusy(itemId);
    try {
      await updateItemStatus(itemId, next);
      await refreshItinerary();
      announceChange(itemId, next, previous, title, false);
    } catch (e: any) {
      if (isNetworkFailure(e)) {
        enqueueStatus({ tripId, itemId, status: next, previousStatus: previous, title });
        announceChange(itemId, next, previous, title, true);
      } else if (isTripConflict(e)) {
        // 409：这条行程在别处被改过，本地状态已过期 → 提示并整页对齐服务端
        await onTripConflict();
      } else {
        message.error(e?.message || '更新行程项状态失败');
      }
    } finally {
      setStatusBusy(null);
    }
  };

  /**
   * 撤销刚做的那一步：
   * 1. 还没同步的变更 → 直接出队（本机什么都没发生，不需要联网）；
   * 2. 已同步的变更 → 把状态写回上一个值（接口是绝对值声明式，天然幂等）；
   * 3. 撤销时恰好离线 → 把「写回」放进队列，联网后照样落地。
   */
  const onUndoStatus = async () => {
    const state = undo;
    if (!state) return;
    setUndo(null);
    const pending = pendingFor(useStatusQueueStore.getState().entries, state.itemId);
    if (pending && pending.status === state.next && pending.previousStatus === state.previous) {
      dropPending(pending.requestId);
      message.success('已撤销待同步记录');
      return;
    }
    if (!online) {
      if (tripId) {
        enqueueStatus({
          tripId,
          itemId: state.itemId,
          status: state.previous,
          previousStatus: state.next,
          title: state.title,
        });
      }
      message.success('已离线撤销，联网后同步');
      return;
    }
    try {
      await updateItemStatus(state.itemId, state.previous);
      await refreshItinerary();
      message.success('已撤销');
    } catch (e: any) {
      if (isTripConflict(e)) {
        await onTripConflict();
      } else {
        message.error(e?.message || '撤销失败');
      }
    }
  };

  /** 队列同步完（联网重放成功）后重拉一次：服务端数据才是权威 */
  const pendingCount = pendingSyncIds.size;
  const lastPendingCount = useRef(pendingCount);
  useEffect(() => {
    const before = lastPendingCount.current;
    lastPendingCount.current = pendingCount;
    if (before > 0 && pendingCount === 0 && online && tripId) {
      loadItinerary(tripId).catch(() => undefined);
      loadRouteLegs(tripId);
    }
  }, [pendingCount, online, tripId, loadItinerary, loadRouteLegs]);

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
                  ? `${currentTrip.destination} · ${daysOf(currentTrip)} 天 · ${currentTrip.partySize ?? 1} 人 · ${tripPhaseLabel(currentTrip)} · ${items.length} 个安排`
                  : '加载中…'}
              </div>
            </div>
          </div>
          <div className="page-actions">
            {pendingCount > 0 && (
              // 离线记录的状态不是「已完成」，只是「本机已记录」：页头先给一句总数
              <span className="chip chip-warn trip-pending-chip" data-testid="trip-pending-sync">
                <CloudSyncOutlined aria-hidden="true" /> {pendingCount} 项待同步
              </span>
            )}
            <span className={`chip ${over ? 'chip-warn' : 'chip-ok'}`}>
              已花 ¥{total.toFixed(0)} / 预算 ¥{budget ?? '-'}
            </span>
            {/* 一级只保留与当前阶段最相关的动作（P1-05）：行中先记账，行前先看预算 */}
            <Button
              type="primary"
              icon={ongoing ? <WalletOutlined /> : <BarChartOutlined />}
              onClick={() => (ongoing ? setExpenseOpen(true) : setBudgetOpen(true))}
            >
              {ongoing ? '记账' : '预算分析'}
            </Button>
            <Dropdown
              trigger={['click']}
              menu={{
                items: [
                  {
                    key: 'undo',
                    icon: <UndoOutlined />,
                    label: '撤销上一次 AI 修改',
                    disabled: undoing,
                    onClick: onUndo,
                  },
                  {
                    key: 'review',
                    icon: <ReadOutlined />,
                    label: '旅行回顾',
                    disabled: !tripId || Number.isNaN(tripId),
                    onClick: () => navigate(`/trip/${tripId}/review`),
                  },
                  {
                    key: 'guidebook',
                    icon: <BookOutlined />,
                    label: '导出路书 PDF',
                    onClick: onExportGuidebook,
                  },
                  ongoing
                    ? {
                        key: 'budget',
                        icon: <BarChartOutlined />,
                        label: '预算分析',
                        onClick: () => setBudgetOpen(true),
                      }
                    : {
                        key: 'expense',
                        icon: <WalletOutlined />,
                        label: '记账',
                        onClick: () => setExpenseOpen(true),
                      },
                ],
              }}
            >
              <Button icon={<MoreOutlined />} aria-label="更多行程操作" />
            </Dropdown>
          </div>
        </div>

        {isMobile ? (
          <>
            {/* 移动端：分段视图，默认行程；下一项与主动作在时间轴顶部（P1-04） */}
            <Segmented
              block
              data-testid="trip-view-switch"
              className="trip-view-switch"
              value={mobileView}
              onChange={(v) => setMobileView(v as MobileView)}
              options={[
                { value: 'itinerary', label: '行程' },
                { value: 'map', label: '地图' },
                { value: 'ai', label: 'AI' },
              ]}
            />
            {mobileView === 'itinerary' && (
              <section className="dash-card trip-timeline-card">
                <Timeline
                  trip={currentTrip}
                  items={items}
                  focus={lastFocus}
                  routeLegs={routeLegs}
                  onItemStatusChange={onItemStatusChange}
                  statusBusyId={statusBusy}
                  pendingSyncIds={pendingSyncIds}
                />
              </section>
            )}
            {mobileView === 'map' && (
              <section className="dash-card trip-map-card">
                <div className="dc-head">
                  <b>地图</b>
                  <span className="mini-tag">
                    <EnvironmentOutlined /> Day {selectedDay}
                  </span>
                </div>
                <div className="trip-map-body">
                  <MapView
                    items={items}
                    focus={lastFocus}
                    focusDay={selectedDay}
                    routeLegs={routeLegs}
                  />
                </div>
              </section>
            )}
            {mobileView === 'ai' && (
              <aside className="dash-card trip-chat-card">
                <div className="dc-head">
                  <b>AI 助手</b>
                  <span className="mini-tag">随时调整行程</span>
                </div>
                <div className="trip-chat-body">
                  <ChatPanel tripId={tripId} placeholder="例如：明天下雨，把户外景点换掉" />
                </div>
              </aside>
            )}
          </>
        ) : (
          /* 桌面：时间轴做主栏，地图与 AI 共享右侧可切换面板（§8.5） */
          <div className="trip-workbench">
            <section className="dash-card trip-timeline-card trip-main-col">
              <Timeline
                trip={currentTrip}
                items={items}
                focus={lastFocus}
                routeLegs={routeLegs}
                onItemStatusChange={onItemStatusChange}
                statusBusyId={statusBusy}
                pendingSyncIds={pendingSyncIds}
              />
            </section>
            <aside className="dash-card trip-side-col">
              <Segmented
                size="small"
                block
                data-testid="trip-side-switch"
                value={sideView}
                onChange={(v) => setSideView(v as 'map' | 'ai')}
                options={[
                  { value: 'map', label: '地图' },
                  { value: 'ai', label: 'AI 助手' },
                ]}
              />
              {/*
                侧栏 keep-alive（§8.5）：地图与 AI 一次挂载、按可见性显隐。
                以前点「AI 助手」会把地图卸载重建，用户缩放/平移到的地方全丢；
                现在只切 visibility，地图实例与相机保持原样。
              */}
              <div className="trip-side-body trip-side-keepalive">
                <div
                  className="trip-pane"
                  data-testid="trip-side-pane-map"
                  data-active={sideView === 'map'}
                  aria-hidden={sideView !== 'map'}
                >
                  <div className="trip-map-body">
                    <MapView
                      items={items}
                      focus={lastFocus}
                      focusDay={selectedDay}
                      routeLegs={routeLegs}
                      active={sideView === 'map'}
                    />
                  </div>
                </div>
                <div
                  className="trip-pane"
                  data-testid="trip-side-pane-ai"
                  data-active={sideView === 'ai'}
                  aria-hidden={sideView !== 'ai'}
                >
                  <div className="trip-chat-body">
                    <ChatPanel tripId={tripId} placeholder="例如：明天下雨，把户外景点换掉" />
                  </div>
                </div>
              </div>
            </aside>
          </div>
        )}
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
      <PlanDiff
        open={!!pendingConfirm}
        result={pendingConfirm}
        onConfirm={onConfirm}
        onCancel={() => cancel()}
        loading={streaming}
      />
      {/* 短时撤销：key=token 让每次操作各自重新计时，不会被上一次续命 */}
      {undo && (
        <UndoBar
          key={undo.token}
          token={undo.token}
          message={undo.message}
          detail={undo.detail}
          onAction={onUndoStatus}
          onExpire={() => setUndo(null)}
        />
      )}
    </AppShell>
  );
}
