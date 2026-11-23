import { useEffect, useMemo, useRef, useState } from 'react';
import { App } from 'antd';
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
import { totalCost, type ItineraryStatus } from '../../types/trip';
import { overlayStatuses, pendingFor, pendingIds } from '../../lib/statusQueue';
import { conflictNotice, isTripConflict } from '../../lib/tripConflict';
import { nextFocusAfter, scrollToItem } from '../../lib/tripFocus';
import AppShell from '../../components/AppShell';
import TripHeader from './components/TripHeader';
import TripWorkspace from './components/TripWorkspace';
import TripDrawers from './components/TripDrawers';
import TripModals from './components/TripModals';
import TripUndoLayer from './components/TripUndoLayer';
import type { MobileView, SideView, UndoState } from './components/types';
import './trip.css';
import './trip-flow.css';

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

/**
 * 行程主界面：工作台式布局 —— 地图 + AI 助手 + 时间轴 + 预算。
 *
 * 页面本身负责数据加载、状态变更与撤销链、待同步队列的协作；
 * 页头、工作区（移动分段 / 桌面主栏+侧栏）与浮层在 components/ 下，只做展示。
 */
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
  const [sideView, setSideView] = useState<SideView>('map');

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
        <TripHeader
          tripId={tripId}
          currentTrip={currentTrip}
          items={items}
          total={total}
          budget={budget}
          over={over}
          ongoing={ongoing}
          pendingCount={pendingCount}
          undo={undo}
          undoing={undoing}
          onBack={() => navigate('/travel')}
          onUndoStep={onUndoStatus}
          onUndoAiChange={onUndo}
          onOpenReview={() => navigate(`/trip/${tripId}/review`)}
          onExportGuidebook={onExportGuidebook}
          onOpenBudget={() => setBudgetOpen(true)}
          onOpenExpense={() => setExpenseOpen(true)}
        />

        <TripWorkspace
          isMobile={isMobile}
          tripId={tripId}
          currentTrip={currentTrip}
          items={items}
          routeLegs={routeLegs}
          focus={lastFocus}
          selectedDay={selectedDay}
          statusBusy={statusBusy}
          pendingSyncIds={pendingSyncIds}
          onItemStatusChange={onItemStatusChange}
          mobileView={mobileView}
          onMobileViewChange={setMobileView}
          sideView={sideView}
          onSideViewChange={setSideView}
          onOpenDiagnostics={() => navigate('/settings/diagnostics')}
        />
      </div>

      <TripDrawers
        tripId={tripId}
        budgetOpen={budgetOpen}
        onBudgetClose={() => setBudgetOpen(false)}
        budgetExecution={budgetExecution}
        items={items}
        budget={budget}
        expenseOpen={expenseOpen}
        onExpenseClose={() => setExpenseOpen(false)}
        total={total}
      />
      <TripModals
        open={!!pendingConfirm}
        result={pendingConfirm}
        onConfirm={onConfirm}
        onCancel={() => cancel()}
        loading={streaming}
      />
      <TripUndoLayer undo={undo} onAction={onUndoStatus} onExpire={() => setUndo(null)} />
    </AppShell>
  );
}
