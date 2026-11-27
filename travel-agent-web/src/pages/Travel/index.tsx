import { useEffect, useState } from 'react';
import { App, Button, Empty, Input, Popconfirm, Tabs, Tooltip } from 'antd';
import {
  CalendarOutlined,
  CarryOutOutlined,
  CheckOutlined,
  CompassOutlined,
  DeleteOutlined,
  GlobalOutlined,
  RobotOutlined,
  StopOutlined,
  UndoOutlined,
  UserOutlined,
} from '@ant-design/icons';
import { useNavigate, useSearchParams } from 'react-router-dom';
import dayjs from 'dayjs';
import { deleteTrip, updateTripStatus } from '../../api/trip';
import { tripPhaseClass, tripPhaseLabel } from '../../types/trip';
import type { Trip } from '../../types/trip';
import {
  canRetry,
  errorResource,
  loadingResource,
  successOrEmpty,
  type ResourceState,
} from '../../lib/resource';
import { useAuthStore } from '../../store/authStore';
import { useTripStore } from '../../store/tripStore';
import VoiceButton from '../../components/VoiceButton';
import Skeleton from '../../components/Skeleton';
import PaginationBar from '../../components/PaginationBar';
import AppShell from '../../components/AppShell';
import EmptyState from '../../components/EmptyState';
import Schedule from '../Schedule';
import Footprint from '../Footprint';

const SUGGESTIONS = [
  '上海三日游，预算3000元，酒店离地铁近一点',
  '周末去杭州玩，2人，帮我规划一下',
  '北京亲子游，玩三天，预算6000元',
];

/** 状态筛选（方案 §8.4）：页面更名为「我的行程」后，用筛选区分待确认/将出发/进行中/已结束 */
type StatusFilter = 'ALL' | 'PLANNING' | 'UPCOMING' | 'ONGOING' | 'PAST' | 'CANCELLED';

const FILTERS: { key: StatusFilter; label: string }[] = [
  { key: 'ALL', label: '全部' },
  { key: 'PLANNING', label: '待确认' },
  { key: 'UPCOMING', label: '即将出发' },
  { key: 'ONGOING', label: '进行中' },
  { key: 'PAST', label: '已结束' },
  { key: 'CANCELLED', label: '已取消' },
];

const PAGE_SIZE = 8;

/** 默认排序：进行中 → 待确认 → 将出发（由近到远）→ 已结束（由近到远）→ 已取消 */
function rankOf(t: Trip): number {
  if (t.phase === 'ONGOING') return 0;
  if (t.status === 'PLANNING' && t.phase !== 'CANCELLED') return 1;
  switch (t.phase) {
    case 'UPCOMING':
      return 2;
    case 'PAST':
      return 3;
    case 'CANCELLED':
      return 4;
    default:
      return 5;
  }
}

function matchesFilter(t: Trip, filter: StatusFilter): boolean {
  switch (filter) {
    case 'ALL':
      return true;
    case 'PLANNING':
      return t.status === 'PLANNING' && t.phase !== 'CANCELLED';
    case 'UPCOMING':
      return t.phase === 'UPCOMING' && t.status !== 'PLANNING';
    case 'ONGOING':
      return t.phase === 'ONGOING';
    case 'PAST':
      return t.phase === 'PAST';
    case 'CANCELLED':
      return t.phase === 'CANCELLED';
    default:
      return true;
  }
}

/**
 * 我的行程（方案 §8.4）。
 * 「日程」「足迹」并入本页 Tabs；页面名与内容范围对齐为「我的行程」，
 * 待确认/即将出发/进行中/已结束/已取消靠筛选区分，而不是全塞进同一列表。
 */
export default function Travel() {
  const { message } = App.useApp();
  const navigate = useNavigate();
  const trips = useTripStore((s) => s.trips);
  const tripsLoading = useTripStore((s) => s.tripsLoading);
  const tripsError = useTripStore((s) => s.tripsError);
  const loadTrips = useTripStore((s) => s.loadTrips);
  const user = useAuthStore((s) => s.user);
  const [text, setText] = useState('');
  const [statusBusy, setStatusBusy] = useState<number | null>(null);
  const [filter, setFilter] = useState<StatusFilter>('ALL');
  const [query, setQuery] = useState('');
  const [page, setPage] = useState(0);
  const [params, setParams] = useSearchParams();
  const tabParam = params.get('tab');
  const activeTab = tabParam === 'schedule' || tabParam === 'footprint' ? tabParam : 'trips';

  // 列表状态按统一模型区分：加载中 / 成功 / 空 / 失败（P0-04）
  const tripsResource: ResourceState<Trip[]> = tripsError
    ? errorResource<Trip[]>(tripsError, { previous: trips })
    : tripsLoading
      ? loadingResource<Trip[]>(trips)
      : successOrEmpty(trips);

  const keyword = query.trim().toLowerCase();
  const filtered = trips
    .filter((t) => matchesFilter(t, filter))
    .filter((t) => {
      if (!keyword) return true;
      const haystack = [t.name, t.destination, t.startDate, t.endDate]
        .filter(Boolean)
        .join(' ')
        .toLowerCase();
      return haystack.includes(keyword);
    })
    .sort((a, b) => {
      const diff = rankOf(a) - rankOf(b);
      if (diff !== 0) return diff;
      const aDate = a.startDate ?? '';
      const bDate = b.startDate ?? '';
      // 已结束/已取消按「最近」在前，其余按「最早出发」在前
      return rankOf(a) >= 3 ? bDate.localeCompare(aDate) : aDate.localeCompare(bDate);
    });

  const pageCount = Math.max(1, Math.ceil(filtered.length / PAGE_SIZE));
  const safePage = Math.min(page, pageCount - 1);
  const pagedTrips = filtered.slice(safePage * PAGE_SIZE, safePage * PAGE_SIZE + PAGE_SIZE);
  const filtering = filter !== 'ALL' || keyword.length > 0;

  useEffect(() => {
    // 失败由 tripsError 渲染（带重试），这里不再弹一次性 toast 掩盖问题
    loadTrips().catch(() => {});
  }, []);

  // 筛选/搜索变化后回到第一页，避免停在越界页码上
  useEffect(() => {
    setPage(0);
  }, [filter, keyword]);

  const start = (value?: string) => {
    const prompt = (value ?? text).trim();
    if (!prompt) {
      message.warning('先告诉我你想去哪里吧');
      return;
    }
    navigate(`/planner?prompt=${encodeURIComponent(prompt)}`);
  };

  const remove = async (id: number) => {
    try {
      await deleteTrip(id);
      message.success('已删除');
      loadTrips();
    } catch (e: any) {
      message.error(e.message);
    }
  };

  const changeStatus = async (id: number, status: string) => {
    setStatusBusy(id);
    try {
      await updateTripStatus(id, status);
      await loadTrips();
      message.success(
        status === 'CANCELLED'
          ? '已标记为取消'
          : status === 'CONFIRMED'
            ? '行程已确认'
            : '已恢复为待确认',
      );
    } catch (e: any) {
      message.error(e?.message || '更新行程状态失败');
    } finally {
      setStatusBusy(null);
    }
  };

  return (
    <AppShell active="travel">
      <div className="dash-wrap">
        <div className="dash-greeting">
          <div>
            <h1>
              <CompassOutlined aria-hidden="true" className="page-title-icon" /> 我的行程
            </h1>
            <div className="dg-date">
              {user ? `${user.nickname ?? user.username} 的旅行空间 · ` : ''}
              待确认、即将出发、进行中与已结束都在这里，用筛选切换
            </div>
          </div>
          <Button type="primary" icon={<RobotOutlined />} onClick={() => navigate('/planner')}>
            新建行程
          </Button>
        </div>

        <Tabs
          activeKey={activeTab}
          onChange={(key) => setParams(key === 'trips' ? {} : { tab: key })}
          items={[
            {
              key: 'trips',
              label: (
                <span>
                  <CarryOutOutlined aria-hidden="true" /> 行程
                </span>
              ),
              children: (
                <>
                  <div className="search-card fade-up">
                    <div className="search-row">
                      <Input
                        size="large"
                        value={text}
                        onChange={(e) => setText(e.target.value)}
                        placeholder="例如：帮我安排一个三天的城市旅行，预算3000元，酒店离地铁近一点"
                        onPressEnter={() => start()}
                        suffix={
                          <span style={{ color: 'var(--text-3)', fontSize: 12 }}>Enter 发送</span>
                        }
                      />
                      <VoiceButton onText={(t) => setText(t)} />
                      <Button
                        size="large"
                        type="primary"
                        icon={<RobotOutlined />}
                        onClick={() => start()}
                      >
                        开始规划
                      </Button>
                    </div>
                    <div className="chip-row">
                      <span className="chip-label">试试：</span>
                      {SUGGESTIONS.map((s) => (
                        <button
                          key={s}
                          type="button"
                          className="suggest-chip"
                          onClick={() => setText(s)}
                        >
                          {s}
                        </button>
                      ))}
                    </div>
                  </div>

                  <div className="list-tools fade-up">
                    <div className="filter-row" role="group" aria-label="按状态筛选行程">
                      {FILTERS.map((f) => (
                        <button
                          key={f.key}
                          type="button"
                          className={`filter-chip ${filter === f.key ? 'active' : ''}`}
                          aria-pressed={filter === f.key}
                          onClick={() => setFilter(f.key)}
                        >
                          {f.label}
                        </button>
                      ))}
                    </div>
                    <Input
                      allowClear
                      value={query}
                      onChange={(e) => setQuery(e.target.value)}
                      placeholder="搜索目的地、名称或日期"
                      aria-label="搜索行程"
                      className="list-search"
                    />
                  </div>

                  <div className="section-head">
                    <h3>
                      我的行程
                      {filtering ? ` · 筛选结果 ${filtered.length} 条` : ''}
                    </h3>
                    <Button
                      type="text"
                      icon={<UserOutlined />}
                      onClick={() => navigate('/profile')}
                    >
                      个人资料
                    </Button>
                  </div>

                  {tripsResource.status === 'error' ? (
                    <div className="load-error" role="alert">
                      <div className="le-title">行程列表加载失败</div>
                      <div className="le-detail">{tripsResource.message}</div>
                      <div className="le-actions">
                        {canRetry(tripsResource) && (
                          <Button
                            size="small"
                            type="primary"
                            onClick={() => loadTrips().catch(() => {})}
                          >
                            重试
                          </Button>
                        )}
                        <Button size="small" onClick={() => navigate('/planner')}>
                          直接规划新行程
                        </Button>
                      </div>
                      {tripsResource.previous && tripsResource.previous.length > 0 && (
                        <div className="le-note">下面是上次加载到的结果，可能不是最新</div>
                      )}
                    </div>
                  ) : null}

                  {tripsResource.status === 'loading' && trips.length === 0 && (
                    <div className="inset-list fade-up" aria-hidden="true">
                      {[0, 1, 2].map((i) => (
                        <Skeleton.ListItem key={i} />
                      ))}
                    </div>
                  )}

                  {tripsResource.status === 'empty' ? (
                    <div className="empty-trips">
                      <EmptyState
                        testId="trips-empty"
                        icon={<CompassOutlined aria-hidden="true" />}
                        title="还没有行程，输入一个旅行想法让 AI 帮你规划吧"
                        hint="点右上角「新建行程」，或用一句话描述你想去哪、几天、几个人。"
                      />
                    </div>
                  ) : null}

                  {/* 有数据但筛选后为空：说明是筛选条件的问题，不是「没有行程」 */}
                  {trips.length > 0 && filtered.length === 0 && (
                    <div className="empty-trips">
                      <Empty
                        image={Empty.PRESENTED_IMAGE_SIMPLE}
                        description={
                          <span>
                            没有符合条件的行程。
                            <button
                              type="button"
                              className="dc-link"
                              onClick={() => {
                                setFilter('ALL');
                                setQuery('');
                              }}
                            >
                              清除筛选
                            </button>
                          </span>
                        }
                      />
                    </div>
                  )}

                  {(tripsResource.status === 'success' || tripsResource.status === 'loading') &&
                  filtered.length > 0 ? (
                    <div className="inset-list fade-up">
                      {pagedTrips.map((t, tripIdx) => {
                        // 一个按钮承载三种动作：待确认 → 确认；已确认（未结束）→ 取消；已取消 → 恢复
                        const action =
                          t.phase === 'CANCELLED'
                            ? { label: '恢复', next: 'CONFIRMED', icon: <UndoOutlined /> }
                            : t.status === 'PLANNING'
                              ? { label: '确认', next: 'CONFIRMED', icon: <CheckOutlined /> }
                              : t.phase === 'PAST'
                                ? null
                                : { label: '取消', next: 'CANCELLED', icon: <StopOutlined /> };
                        return (
                          <div
                            key={t.id}
                            className="trip-row stagger-item"
                            style={{ animationDelay: `${Math.min(tripIdx * 0.06, 0.4)}s` }}
                          >
                            {/* 打开详情是主区域，用真按钮承载：键盘可 Tab 到、可回车打开（P0-05） */}
                            <button
                              type="button"
                              className="tr-open"
                              onClick={() => navigate(`/trip/${t.id}`)}
                            >
                              <span
                                className="tr-icon"
                                style={{ background: 'var(--grad-primary)' }}
                              >
                                {(t.destination || '旅')[0]}
                              </span>
                              <span className="tr-main">
                                <span className="tr-title">
                                  <span className="tr-name">{t.name}</span>
                                  <span className={`mini-tag ${tripPhaseClass(t)}`}>
                                    {tripPhaseLabel(t)}
                                  </span>
                                  {t.status === 'PLANNING' && t.phase !== 'CANCELLED' && (
                                    <span className="mini-tag">待确认</span>
                                  )}
                                </span>
                                <span className="tr-sub">
                                  {t.destination ?? '未设置'} ·{' '}
                                  {dayjs(t.startDate).format('MM月DD日')} -{' '}
                                  {dayjs(t.endDate).format('MM月DD日')}
                                </span>
                              </span>
                              <span className="tr-chevron" aria-hidden="true">
                                ›
                              </span>
                            </button>
                            <div className="tr-right">
                              <span className="tr-budget">¥{t.budget}</span>
                              {action && (
                                <Button
                                  type="text"
                                  size="small"
                                  loading={statusBusy === t.id}
                                  icon={action.icon}
                                  onClick={() => changeStatus(t.id, action.next)}
                                >
                                  {action.label}
                                </Button>
                              )}
                              {/* 删除是低频动作：图标按钮 + Popconfirm，不与状态/预算抢视觉权重 */}
                              <Tooltip title="删除行程">
                                <Popconfirm
                                  title="确定删除该行程？"
                                  description={`「${t.name}」删除后无法恢复`}
                                  okText="删除"
                                  okButtonProps={{ danger: true }}
                                  cancelText="取消"
                                  onConfirm={() => remove(t.id)}
                                >
                                  <Button
                                    type="text"
                                    danger
                                    size="small"
                                    icon={<DeleteOutlined />}
                                    aria-label={`删除行程 ${t.name}`}
                                  />
                                </Popconfirm>
                              </Tooltip>
                            </div>
                          </div>
                        );
                      })}
                    </div>
                  ) : null}

                  {/* 客户端分页：历史行程不一次性渲染，翻页只改偏移 */}
                  {filtered.length > PAGE_SIZE && (
                    <PaginationBar
                      total={filtered.length}
                      offset={safePage * PAGE_SIZE}
                      limit={PAGE_SIZE}
                      hasMore={(safePage + 1) * PAGE_SIZE < filtered.length}
                      paged
                      onPrev={() => setPage(Math.max(0, safePage - 1))}
                      onNext={() => setPage(Math.min(pageCount - 1, safePage + 1))}
                    />
                  )}
                </>
              ),
            },
            {
              key: 'schedule',
              label: (
                <span>
                  <CalendarOutlined aria-hidden="true" /> 日程
                </span>
              ),
              children: <Schedule />,
            },
            {
              key: 'footprint',
              label: (
                <span>
                  <GlobalOutlined aria-hidden="true" /> 足迹
                </span>
              ),
              children: <Footprint />,
            },
          ]}
        />
      </div>
    </AppShell>
  );
}
