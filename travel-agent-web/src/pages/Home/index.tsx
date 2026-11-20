import { lazy, Suspense, useEffect, useState } from 'react';
import { App, Button, Input, Tag } from 'antd';
import {
  CalendarOutlined,
  CompassOutlined,
  PlusOutlined,
  SendOutlined,
  WalletOutlined,
} from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import dayjs from 'dayjs';
import { getTripsMap } from '../../api/trip';
import { getExpenseOverview } from '../../api/expense';
import { getMemos, toggleMemo } from '../../api/memo';
import { listSchedule, type ScheduleItem } from '../../api/assistant';
import { getReminders, type ReminderBoard } from '../../api/reminder';
import { useAuthStore } from '../../store/authStore';
import { useUIStore } from '../../store/uiStore';
import AppShell from '../../components/AppShell';
import CityDrawer from '../../components/CityDrawer';
import VoiceButton from '../../components/VoiceButton';
import CountUp from '../../components/CountUp';
import Skeleton from '../../components/Skeleton';
import {
  footprintStats,
  tripDays,
  visitedPoints,
} from '../../components/FootprintMap/footprintData';
import type { ExpenseOverview } from '../../types/expense';
import type { Memo } from '../../types/memo';
import type { TripMapPoint } from '../../types/trip';

// 首页足迹地图依赖 maplibre-gl（约 1MB），懒加载避免拖慢首屏
const FootprintMap = lazy(() => import('../../components/FootprintMap'));

const EXAMPLE_PROMPT = '帮我规划周末上海两日游，2人';

/**
 * 首页（方案 §8.2）：只回答三个问题 ——
 * 1) 我下一次旅行是什么；2) 我今天需要做什么；3) 我现在能让 AI 帮我做什么。
 * 诊断类信息（最近 Agent 运行）、完整统计与低优先级卡片已移出首屏。
 */
export default function Home() {
  const { message } = App.useApp();
  const navigate = useNavigate();
  const user = useAuthStore((s) => s.user);

  const [prompt, setPrompt] = useState('');
  const [mapPoints, setMapPoints] = useState<TripMapPoint[] | null>(null);
  const [overview, setOverview] = useState<ExpenseOverview | null>(null);
  const [memos, setMemos] = useState<Memo[]>([]);
  const [schedule, setSchedule] = useState<ScheduleItem[]>([]);
  const [reminderBoard, setReminderBoard] = useState<ReminderBoard | null>(null);
  const [statsLoading, setStatsLoading] = useState(true);
  /** 加载失败的业务模块：失败要显示「—」，不能被降级成 0 或「暂无数据」（P0-04） */
  const [failed, setFailed] = useState<string[]>([]);
  const [reloadKey, setReloadKey] = useState(0);
  const [selectedCity, setSelectedCity] = useState<string | null>(null);
  const [playSignal, setPlaySignal] = useState(0);
  const setRemindersOpen = useUIStore((s) => s.setRemindersOpen);
  const reminderCount = reminderBoard?.count ?? 0;
  const failedModule = (key: string) => failed.includes(key);

  useEffect(() => {
    let alive = true;
    // 每个模块单独记录成败：一个接口失败不能拖垮整页，也不能伪装成「空」
    const failures: string[] = [];
    const safe = <T,>(key: string, p: Promise<T>, fallback: T): Promise<T> =>
      p.catch(() => {
        failures.push(key);
        return fallback;
      });
    setStatsLoading(true);
    Promise.all([
      safe('footprint', getTripsMap(), []),
      safe('expense', getExpenseOverview(), null),
      safe('memos', getMemos(), []),
      safe('schedule', listSchedule(), []),
      safe('reminders', getReminders(), null),
    ]).then(([mp, ov, mm, sc, rem]) => {
      if (!alive) return;
      setMapPoints(mp);
      setOverview(ov);
      setMemos(mm);
      setSchedule(sc);
      setReminderBoard(rem);
      setFailed(failures);
      setStatsLoading(false);
    });
    return () => {
      alive = false;
    };
  }, [reloadKey]);

  const today = dayjs().format('YYYY-MM-DD');
  const openMemos = memos.filter((m) => !m.done);
  const todaySchedule = schedule
    .filter((s) => s.date === today && !s.done)
    .sort((a, b) => (a.time ?? '').localeCompare(b.time ?? ''));

  const allPoints = mapPoints ?? [];
  // 足迹只认真实打卡：未来/计划行程绝不能算成「去过」
  const visitedMapPoints = visitedPoints(allPoints);
  const stats = footprintStats(visitedMapPoints);

  /** tripId -> 实际记账金额，用于城市详情里的「计划 vs 实际」 */
  const spendByTrip: Record<number, number> = {};
  (overview?.byTrip ?? []).forEach((t) => {
    if (t.tripId > 0) spendByTrip[t.tripId] = Number(t.total ?? 0);
  });

  const activeTrips = allPoints.filter((p) => p.phase !== 'CANCELLED' && p.startDate);

  // 下一次旅行：进行中优先，其次按出发时间最近的未来行程
  const ongoingTrip = activeTrips.find(
    (p) => p.startDate! <= today && (p.endDate ?? p.startDate!) >= today,
  );
  const upcomingTrip = activeTrips
    .filter((p) => p.startDate! > today)
    .sort((a, b) => a.startDate!.localeCompare(b.startDate!))[0];
  const nextTrip = ongoingTrip ?? upcomingTrip ?? null;
  const planningTrips = activeTrips
    .filter((p) => p.status === 'PLANNING')
    .sort((a, b) => (a.startDate ?? '').localeCompare(b.startDate ?? ''));

  const send = (value?: string) => {
    const p = (value ?? prompt).trim();
    if (!p) {
      message.warning('告诉我你想完成什么');
      return;
    }
    navigate(`/assistant?q=${encodeURIComponent(p)}`);
    setPrompt('');
  };

  /** 首页待办卡：直接勾选完成，和备忘模块共用同一份数据 */
  const onToggleMemo = async (memo: Memo) => {
    try {
      const updated = await toggleMemo(memo.id);
      setMemos((prev) => prev.map((m) => (m.id === memo.id ? updated : m)));
    } catch (e: any) {
      message.error(e.message);
    }
  };

  /** 今日概览：三项指标 + 紧凑命令菜单 */
  const overviewItems = [
    {
      key: 'schedule',
      label: '今日安排',
      value: `${todaySchedule.length}`,
      unit: '项',
      path: '/travel?tab=schedule',
    },
    {
      key: 'expense',
      label: '本月支出',
      value: `¥${(overview?.monthSpent ?? 0).toFixed(0)}`,
      path: '/expenses',
    },
    {
      key: 'reminders',
      label: '待提醒',
      value: `${reminderCount}`,
      unit: '项',
      action: () => setRemindersOpen(true),
    },
  ];

  const nextTripDays = nextTrip?.startDate
    ? dayjs(nextTrip.startDate).startOf('day').diff(dayjs(today), 'day')
    : null;

  return (
    <AppShell active="home">
      <div className="dash-wrap">
        <div className="dash-greeting">
          <div>
            <h1>
              {dayjs().hour() < 12 ? '早上好' : dayjs().hour() < 18 ? '下午好' : '晚上好'}，
              {user?.nickname ?? user?.username ?? '朋友'} 👋
            </h1>
            <div className="dg-date">告诉我你想完成什么？</div>
          </div>
          <Button
            type="primary"
            icon={<PlusOutlined />}
            onClick={() => navigate('/planner')}
            data-testid="home-new-trip"
          >
            新建行程
          </Button>
        </div>

        {/* ✨ 全局命令输入：首屏唯一的主输入 */}
        <div className="assistant-quick fade-up">
          <div className="aq-row">
            <span className="aq-icon" aria-hidden="true">
              ✨
            </span>
            <Input
              size="large"
              value={prompt}
              onChange={(e) => setPrompt(e.target.value)}
              placeholder="告诉我你想完成什么……"
              onPressEnter={() => send()}
              variant="borderless"
              className="aq-input"
              aria-label="告诉 AI 你想完成什么"
            />
            <VoiceButton onText={(t) => setPrompt(t)} />
            <Button
              type="primary"
              size="large"
              shape="circle"
              icon={<SendOutlined />}
              onClick={() => send()}
              disabled={!prompt.trim()}
              aria-label="发送给 AI 助手"
            />
          </div>
          <div className="aq-hint">
            <button
              type="button"
              className="suggest-chip"
              onClick={() => setPrompt(EXAMPLE_PROMPT)}
            >
              {EXAMPLE_PROMPT}
            </button>
            <button type="button" className="suggest-chip" onClick={() => navigate('/memos')}>
              记一笔备忘
            </button>
            <button type="button" className="suggest-chip" onClick={() => navigate('/knowledge')}>
              查攻略库
            </button>
          </div>
        </div>

        {/* 局部失败：说明哪些模块没加载出来，并给一个重试入口；成功模块照常可用（P0-04） */}
        {!statsLoading && failed.length > 0 && (
          <div className="load-error fade-up" role="alert">
            <div className="le-title">部分内容加载失败</div>
            <div className="le-detail">
              标有「加载失败」的指标与被影响的卡片暂时不可信，其余模块仍可正常使用。
            </div>
            <div className="le-actions">
              <Button size="small" type="primary" onClick={() => setReloadKey((v) => v + 1)}>
                重试
              </Button>
            </div>
          </div>
        )}

        {/* ① 下一次旅行 */}
        <section className="next-trip fade-up" data-testid="home-next-trip">
          {statsLoading && !nextTrip ? (
            <div className="nt-body">
              <Skeleton.Text width={160} height={20} />
              <Skeleton.Text width={240} height={14} />
            </div>
          ) : nextTrip ? (
            <div className="nt-body">
              <div className="nt-head">
                <span className="nt-eyebrow">{ongoingTrip ? '正在进行' : '下一次旅行'}</span>
                <Tag color={ongoingTrip ? 'green' : 'blue'} style={{ marginInlineEnd: 0 }}>
                  {ongoingTrip
                    ? `第 ${Math.max(
                        1,
                        dayjs(today).diff(dayjs(nextTrip.startDate), 'day') + 1,
                      )} 天`
                    : nextTripDays === 0
                      ? '今天出发'
                      : `${nextTripDays} 天后出发`}
                </Tag>
              </div>
              <div className="nt-title">
                {nextTrip.name ?? nextTrip.destination ?? '未命名行程'}
              </div>
              <div className="nt-meta">
                {nextTrip.destination ?? '未设置'} · {nextTrip.startDate ?? '日期待定'} -{' '}
                {nextTrip.endDate ?? '待定'} · {tripDays(nextTrip)} 天
                {nextTrip.budget != null ? ` · 预算 ¥${nextTrip.budget}` : ''}
              </div>
              <div className="nt-actions">
                <Button
                  type="primary"
                  onClick={() => navigate(`/trip/${nextTrip.tripId}`)}
                  data-testid="home-open-trip"
                >
                  查看行程
                </Button>
                <Button onClick={() => navigate('/travel?tab=schedule')}>准备清单与日程</Button>
              </div>
            </div>
          ) : failedModule('footprint') ? (
            <div className="nt-body">
              <div className="nt-title">行程数据加载失败</div>
              <div className="nt-meta">重试后再看你的下一次旅行。</div>
              <div className="nt-actions">
                <Button onClick={() => setReloadKey((v) => v + 1)}>重试</Button>
              </div>
            </div>
          ) : (
            <div className="nt-body">
              <div className="nt-title">还没有安排中的旅行</div>
              <div className="nt-meta">
                说出目的地和天数，AI 会给出路线、预算与风险，再交给你确认。
              </div>
              <div className="nt-actions">
                <Button
                  type="primary"
                  onClick={() => navigate('/planner')}
                  data-testid="home-plan-first"
                >
                  规划第一次旅行
                </Button>
              </div>
            </div>
          )}
        </section>

        {/* ② 今天要做什么 ③ 待处理 */}
        <div className="dash-grid">
          <section className="dash-card">
            <div className="dc-head">
              <b>
                <CalendarOutlined /> 今天要做什么
              </b>
              <button
                type="button"
                className="dc-link"
                onClick={() => navigate('/travel?tab=schedule')}
              >
                全部日程 →
              </button>
            </div>
            {todaySchedule.length === 0 && openMemos.length === 0 ? (
              <div className="hss-note">今天没有安排。说一句「记一下明天办签证」就能加备忘。</div>
            ) : (
              <>
                {todaySchedule.slice(0, 4).map((s) => (
                  <div key={s.id} className="today-row">
                    <span className="today-time">{s.time ? s.time.slice(0, 5) : '全天'}</span>
                    <span className="today-title">{s.title}</span>
                  </div>
                ))}
                {openMemos.length > 0 && (
                  <div className="todo-list">
                    {openMemos.slice(0, 5).map((m) => (
                      <label key={m.id} className="todo-item">
                        <input
                          type="checkbox"
                          checked={m.done}
                          onChange={() => onToggleMemo(m)}
                          aria-label={`完成备忘：${m.content}`}
                        />
                        <span>{m.content}</span>
                      </label>
                    ))}
                  </div>
                )}
              </>
            )}
            {openMemos.length > 0 && (
              <button
                type="button"
                className="dc-link block-link"
                onClick={() => navigate('/memos')}
              >
                全部待办 →
              </button>
            )}
          </section>

          <section className="dash-card" data-testid="home-todo">
            <div className="dc-head">
              <b>待处理</b>
            </div>
            <button
              type="button"
              className="pending-row"
              onClick={() => navigate('/travel')}
              data-testid="pending-plans"
            >
              <span className="pr-main">
                <span className="pr-num">
                  {failedModule('footprint') ? '—' : planningTrips.length}
                </span>
                <span className="pr-label">个方案待确认</span>
              </span>
              <span className="pr-arrow" aria-hidden="true">
                ›
              </span>
            </button>
            <button type="button" className="pending-row" onClick={() => setRemindersOpen(true)}>
              <span className="pr-main">
                <span className="pr-num">{failedModule('reminders') ? '—' : reminderCount}</span>
                <span className="pr-label">个提醒待处理</span>
              </span>
              <span className="pr-arrow" aria-hidden="true">
                ›
              </span>
            </button>
            <div className="pending-row as-text">
              <span className="pr-main">
                <span className="pr-label">
                  {failedModule('footprint')
                    ? '足迹数据加载失败，重试后可见'
                    : stats.located < stats.trips
                      ? `${stats.trips - stats.located} 个行程未定位到坐标`
                      : '所有行程都已定位到坐标'}
                </span>
              </span>
            </div>
          </section>
        </div>

        {/* ④ 今日概览：一行指标 + 紧凑命令菜单，不再用四张同权重大卡 */}
        <section className="today-strip fade-up" data-testid="home-overview">
          <div className="ts-items">
            {overviewItems.map((item) => (
              <button
                key={item.key}
                type="button"
                className="ts-item"
                onClick={() => (item.action ? item.action() : navigate(item.path!))}
              >
                <span className="ts-value">
                  {statsLoading ? (
                    <Skeleton.Text width={46} height={18} />
                  ) : failedModule(item.key) ? (
                    '—'
                  ) : item.value.startsWith('¥') ? (
                    <CountUp
                      value={parseFloat(item.value.replace(/[^0-9.]/g, '')) || 0}
                      prefix="¥"
                      duration={700}
                    />
                  ) : (
                    item.value
                  )}
                </span>
                <span className="ts-label">
                  {item.label}
                  {item.unit ? ` · ${item.unit}` : ''}
                  {!statsLoading && failedModule(item.key) ? ' · 加载失败' : ''}
                </span>
              </button>
            ))}
          </div>
          <div className="ts-actions">
            <Button icon={<WalletOutlined />} onClick={() => navigate('/expenses')}>
              记一笔
            </Button>
            <Button icon={<CalendarOutlined />} onClick={() => navigate('/travel?tab=schedule')}>
              加日程
            </Button>
            <Button icon={<CompassOutlined />} onClick={() => navigate('/travel')}>
              我的行程
            </Button>
          </div>
        </section>

        {/* ⑤ 足迹摘要：没有足迹时只占一行引导，不再用空地图占据首屏 */}
        <section className="dash-card fade-up">
          <div className="dc-head">
            <b>🌍 我的足迹</b>
            <button
              type="button"
              className="dc-link"
              onClick={() => navigate('/travel?tab=footprint')}
            >
              全屏 →
            </button>
          </div>
          {failedModule('footprint') ? (
            <div className="hss-note">足迹数据加载失败，重试后再看</div>
          ) : mapPoints === null ? (
            <div className="hss-note">正在定位目的地…</div>
          ) : stats.located === 0 ? (
            <div className="hss-note">
              {visitedMapPoints.length > 0
                ? '已打卡的地点还没定位到坐标，打开行程补充地点位置吧'
                : '还没有实际打卡的足迹，完成行程项后这里才会亮起'}
            </div>
          ) : (
            <>
              <div className="footprint-slim">
                {stats.cities} 城 · {stats.trips} 次出行 · {stats.days} 天
                <button
                  type="button"
                  className="dc-link"
                  onClick={() => setPlaySignal((v) => v + 1)}
                >
                  ▶ 回放足迹
                </button>
              </div>
              <div className="hg-map footprint-slim-map">
                <Suspense fallback={<div className="footprint-loading">地图加载中…</div>}>
                  <FootprintMap
                    points={visitedMapPoints}
                    mode="city"
                    theme="light"
                    pitch={60}
                    bearing={-18}
                    zoom={2.3}
                    autoSpin
                    showControls={false}
                    playSignal={playSignal}
                    onCityClick={setSelectedCity}
                  />
                </Suspense>
              </div>
            </>
          )}
        </section>
      </div>

      <CityDrawer
        city={selectedCity}
        points={visitedMapPoints}
        spendByTrip={spendByTrip}
        onClose={() => setSelectedCity(null)}
        onOpenTrip={(tripId) => navigate(`/trip/${tripId}`)}
        onAskAi={(question, tripId) => {
          setSelectedCity(null);
          const suffix = tripId ? `&tripId=${tripId}` : '';
          navigate(`/assistant?q=${encodeURIComponent(question)}${suffix}`);
        }}
      />
    </AppShell>
  );
}
