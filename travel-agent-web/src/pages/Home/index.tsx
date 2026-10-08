import { lazy, Suspense, useEffect, useState } from 'react';
import { App, Button, Empty, Input } from 'antd';
import { SendOutlined } from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import dayjs from 'dayjs';
import { getTripsMap } from '../../api/trip';
import { getExpenseOverview } from '../../api/expense';
import { getMemos, toggleMemo } from '../../api/memo';
import { healthSummary, listArtifacts, listMinutes, listReports, listSchedule, type ScheduleItem } from '../../api/assistant';
import { useAuthStore } from '../../store/authStore';
import AppShell from '../../components/AppShell';
import CityDrawer from '../../components/CityDrawer';
import VoiceButton from '../../components/VoiceButton';
import {
  filterByPeriod,
  footprintMonths,
  footprintStats,
  footprintYears,
  tripDays,
} from '../../components/FootprintMap/footprintData';
import type { ExpenseOverview } from '../../types/expense';
import type { Memo } from '../../types/memo';
import type { TripMapPoint } from '../../types/trip';
import type { HealthSummary } from '../../types/assistant';

// 首页足迹地图依赖 maplibre-gl（约 1MB），懒加载避免拖慢首屏
const FootprintMap = lazy(() => import('../../components/FootprintMap'));

const EXAMPLE_PROMPT = '帮我规划周末上海两日游';

/** 总览：3D 足迹为核心视觉的个人助理 Dashboard */
export default function Home() {
  const { message } = App.useApp();
  const navigate = useNavigate();
  const user = useAuthStore((s) => s.user);

  const [prompt, setPrompt] = useState('');
  const [mapPoints, setMapPoints] = useState<TripMapPoint[] | null>(null);
  const [overview, setOverview] = useState<ExpenseOverview | null>(null);
  const [memos, setMemos] = useState<Memo[]>([]);
  const [schedule, setSchedule] = useState<ScheduleItem[]>([]);
  const [aiCount, setAiCount] = useState(0);
  const [health, setHealth] = useState<HealthSummary | null>(null);

  // 时间轴：年份/月份驱动地图
  const [periodYear, setPeriodYear] = useState<string | null>(null);
  const [periodMonth, setPeriodMonth] = useState<string | null>(null);
  const [playSignal, setPlaySignal] = useState(0);
  const [selectedCity, setSelectedCity] = useState<string | null>(null);

  useEffect(() => {
    const safe = (p: Promise<any>, fallback: any) => p.catch(() => fallback);
    Promise.all([
      safe(getTripsMap(), []),
      safe(getExpenseOverview(), null),
      safe(getMemos(), []),
      safe(listSchedule(), []),
      safe(healthSummary(), null),
      safe(listMinutes(), []),
      safe(listReports(), []),
      safe(listArtifacts(), []),
    ]).then(([mp, ov, mm, sc, hl, min, rp, art]) => {
      setMapPoints(mp);
      setOverview(ov);
      setMemos(mm);
      setSchedule(sc);
      setHealth(hl);
      setAiCount(min.length + rp.length + art.length);
    });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const today = dayjs().format('YYYY-MM-DD');
  const openMemos = memos.filter((m) => !m.done);
  const todaySchedule = schedule.filter((s) => s.date === today && !s.done);
  const upcoming = schedule
    .filter((s) => !s.done && s.date >= today)
    .sort((a, b) => (a.date + (a.time ?? '')).localeCompare(b.date + (b.time ?? '')))
    .slice(0, 4);

  const allPoints = mapPoints ?? [];
  const years = footprintYears(allPoints);
  const pointsInYear = periodYear ? filterByPeriod(allPoints, periodYear, null) : allPoints;
  const months = footprintMonths(pointsInYear);
  const visiblePoints = filterByPeriod(allPoints, periodYear, periodMonth);
  const stats = footprintStats(visiblePoints);

  /** tripId -> 实际记账金额，用于城市详情里的「计划 vs 实际」 */
  const spendByTrip: Record<number, number> = {};
  (overview?.byTrip ?? []).forEach((t) => {
    if (t.tripId > 0) spendByTrip[t.tripId] = Number(t.total ?? 0);
  });

  const recentTrips = allPoints
    .filter((p) => p.startDate)
    .slice()
    .sort((a, b) => (b.startDate ?? '').localeCompare(a.startDate ?? ''))
    .slice(0, 4);

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

  const todayStats = [
    { icon: '📅', cls: 'blue', label: '今日安排', num: String(todaySchedule.length), sub: '项', path: '/travel?tab=schedule' },
    { icon: '💰', cls: 'orange', label: '本月支出', num: `¥${(overview?.monthSpent ?? 0).toFixed(0)}`, path: '/expenses' },
    { icon: '🤖', cls: 'green', label: 'AI 任务', num: String(aiCount), sub: '项', path: '/workbench' },
    { icon: '⏰', cls: 'purple', label: '待提醒', num: String(openMemos.length), sub: '项', path: '/memos' },
  ];

  // AI 今日建议：由真实数据推导
  const suggestions: string[] = [];
  if (openMemos.length > 0) suggestions.push(`发现 ${openMemos.length} 项待处理任务，说「我的备忘」即可查看`);
  if (todaySchedule.length > 0) suggestions.push(`今天有 ${todaySchedule.length} 项日程安排，注意预留通勤时间`);
  const monthSpent = overview?.monthSpent ?? 0;
  if (monthSpent > 0) suggestions.push(`本月已支出 ¥${monthSpent.toFixed(0)}，可在记账模块查看渠道分布`);
  if ((health?.avgSteps ?? 99999) < 6000) suggestions.push('最近步数偏少，建议每天散步 30 分钟');
  if (allPoints.length > 1 && stats.cities > 1) suggestions.push(`足迹已覆盖 ${stats.cities} 座城市，点击地图节点可查看单一城市详情`);
  if (suggestions.length === 0) suggestions.push('一切井然有序，去规划下一场旅行吧！');

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
        </div>

        {/* ✨ 助手输入 */}
        <div className="assistant-quick lg-sweep fade-up">
          <div className="aq-row">
            <span className="aq-icon">✨</span>
            <Input
              size="large"
              value={prompt}
              onChange={(e) => setPrompt(e.target.value)}
              placeholder="告诉我你想完成什么……"
              onPressEnter={() => send()}
              variant="borderless"
              className="aq-input"
            />
            <VoiceButton onText={(t) => setPrompt(t)} />
            <Button
              type="primary"
              size="large"
              shape="circle"
              icon={<SendOutlined />}
              onClick={() => send()}
              disabled={!prompt.trim()}
            />
          </div>
          <div className="aq-hint">
            <span className="suggest-chip" onClick={() => setPrompt(EXAMPLE_PROMPT)}>
              {EXAMPLE_PROMPT}
            </span>
            <span className="suggest-chip" onClick={() => navigate('/travel')}>
              🗺️ 去规划
            </span>
            <span className="suggest-chip" onClick={() => navigate('/memos')}>
              📝 我的待办
            </span>
          </div>
        </div>

        {/* 今日概览 */}
        <div className="stat-row">
          {todayStats.map((s) => (
            <div key={s.label} className="stat-card lg-sweep" onClick={() => navigate(s.path)}>
              <div className={`sc-icon ${s.cls}`}>{s.icon}</div>
              <div className="sc-num">{s.num}</div>
              <div className="sc-label">
                {s.label}
                {s.sub ? ` · ${s.sub}` : ''}
              </div>
            </div>
          ))}
        </div>

        {/* 核心视觉：3D 足迹（深色块） + 右侧信息栏 */}
        <div className="hero-grid">
          <div className="hero-main">
          <section className="hero-globe lg-sweep">
            <div className="hg-head">
              <b>🌍 我的足迹</b>
              <span>
                {periodYear ? `${periodYear} 年 · ` : ''}
                {stats.cities} 城 · {stats.trips} 次出行 · {stats.days} 天
              </span>
            </div>
            <div className="hg-map">
              {mapPoints === null ? (
                <div className="footprint-loading">正在定位目的地…</div>
              ) : stats.located === 0 ? (
                <div className="footprint-loading">
                  {periodYear ? '这个时间段还没有足迹，换个年份看看' : '还没有行程，去「旅行」创建一个吧'}
                </div>
              ) : (
                <Suspense fallback={<div className="footprint-loading">地图加载中…</div>}>
                  <FootprintMap
                    points={visiblePoints}
                    mode="city"
                    /* 纸质底图是这块的视觉主张：暗色界面下也保留「桌面上的地图纸」观感 */
                    theme="light"
                    pitch={48}
                    bearing={-18}
                    autoSpin
                    showControls={false}
                    playSignal={playSignal}
                    onCityClick={setSelectedCity}
                  />
                </Suspense>
              )}
            </div>
            <div className="hg-timeline">
              <button
                type="button"
                className={`tl-chip ${!periodYear ? 'active' : ''}`}
                onClick={() => {
                  setPeriodYear(null);
                  setPeriodMonth(null);
                }}
              >
                全部年份
              </button>
              {years.map((year) => (
                <button
                  type="button"
                  key={year}
                  className={`tl-chip ${periodYear === year ? 'active' : ''}`}
                  onClick={() => {
                    setPeriodYear(periodYear === year ? null : year);
                    setPeriodMonth(null);
                  }}
                >
                  {year}
                </button>
              ))}
              {periodYear &&
                months.map((month) => (
                  <button
                    type="button"
                    key={month}
                    className={`tl-chip small ${periodMonth === month ? 'active' : ''}`}
                    onClick={() => setPeriodMonth(periodMonth === month ? null : month)}
                  >
                    {Number(month)}月
                  </button>
                ))}
              <span className="tl-spacer" />
              <button type="button" className="tl-chip action" onClick={() => setPlaySignal((v) => v + 1)}>
                ▶ 回放足迹
              </button>
              <button type="button" className="tl-chip action" onClick={() => navigate('/travel?tab=footprint')}>
                全屏 →
              </button>
            </div>
          </section>

          <section className="dash-card">
            <div className="dc-head">
              <b>📝 今日待办</b>
              <button className="dc-link" onClick={() => navigate('/memos')}>
                全部 →
              </button>
            </div>
            {openMemos.length === 0 ? (
              <div className="hss-note">暂无待办，说一句「记一下明天办签证」就能添加</div>
            ) : (
              <div className="todo-list">
                {openMemos.slice(0, 5).map((m) => (
                  <label key={m.id} className="todo-item">
                    <input type="checkbox" checked={m.done} onChange={() => onToggleMemo(m)} />
                    <span>{m.content}</span>
                  </label>
                ))}
              </div>
            )}
          </section>
          </div>

          <aside className="hero-side">
            <div className="dash-card lg-sweep">
              <div className="dc-head">
                <b>📊 足迹数据</b>
                <button className="dc-link" onClick={() => navigate('/travel?tab=footprint')}>
                  足迹页 →
                </button>
              </div>
              <div className="hero-side-stats">
                <div className="hss-item">
                  <span className="hss-num">{stats.cities}</span>
                  <span className="hss-label">城市</span>
                </div>
                <div className="hss-item">
                  <span className="hss-num">{stats.trips}</span>
                  <span className="hss-label">出行</span>
                </div>
                <div className="hss-item">
                  <span className="hss-num">{stats.days}</span>
                  <span className="hss-label">天数</span>
                </div>
                <div className="hss-item">
                  <span className="hss-num">¥{(stats.budget / 1000).toFixed(1)}k</span>
                  <span className="hss-label">预算</span>
                </div>
              </div>
              {stats.located < stats.trips && (
                <div className="hss-note">{stats.trips - stats.located} 个行程未定位到坐标</div>
              )}
            </div>

            <div className="dash-card lg-sweep">
              <div className="dc-head">
                <b>✨ AI 今日建议</b>
              </div>
              {suggestions.slice(0, 3).map((s, i) => (
                <div key={i} className="recent-expense-row" style={{ padding: '8px 4px', cursor: 'default' }}>
                  <span style={{ color: 'var(--blue)', flexShrink: 0 }}>✦</span>
                  <span style={{ fontSize: 13, color: 'var(--ink-2)', lineHeight: 1.6 }}>{s}</span>
                </div>
              ))}
            </div>

            <div className="dash-card lg-sweep">
              <div className="dc-head">
                <b>🧭 最近行程</b>
                <button className="dc-link" onClick={() => navigate('/travel')}>
                  全部 →
                </button>
              </div>
              {recentTrips.length === 0 ? (
                <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="还没有行程" />
              ) : (
                recentTrips.map((trip) => (
                  <button
                    type="button"
                    key={trip.tripId}
                    className="recent-trip"
                    onClick={() => trip.destination && setSelectedCity(trip.destination)}
                  >
                    <span className="rt-dot" />
                    <span className="rt-city">{trip.destination ?? trip.name ?? '未命名'}</span>
                    <span className="rt-meta">
                      {trip.startDate ?? '日期待定'} · {tripDays(trip)} 天
                    </span>
                  </button>
                ))
              )}
              <div className="hss-note" style={{ marginTop: 6 }}>
                点击地图上的城市节点或这里的城市，查看该城市足迹
              </div>
            </div>

            {upcoming.length > 0 && (
              <div className="dash-card lg-sweep">
                <div className="dc-head">
                  <b>📅 即将到来</b>
                  <button className="dc-link" onClick={() => navigate('/travel?tab=schedule')}>
                    日程 →
                  </button>
                </div>
                {upcoming.map((s) => (
                  <div key={s.id} className="recent-expense-row" style={{ padding: '7px 4px', cursor: 'default' }}>
                    <span className="mini-tag" style={{ flexShrink: 0 }}>
                      {s.date === today ? '今天' : s.date.slice(5)}
                    </span>
                    <span className="rt-meta" style={{ flex: 1, textAlign: 'left' }}>
                      {s.time ? `${s.time.slice(0, 5)} ` : ''}
                      {s.title}
                    </span>
                  </div>
                ))}
              </div>
            )}

            <div className="dash-card">
              <div className="dc-head">
                <b>⚡ 快捷入口</b>
              </div>
              <div className="quick-grid">
                <button type="button" className="quick-btn" onClick={() => navigate('/expenses')}>
                  💰 记一笔
                </button>
                <button type="button" className="quick-btn" onClick={() => navigate('/memos')}>
                  📝 加备忘
                </button>
                <button type="button" className="quick-btn" onClick={() => navigate('/travel?tab=schedule')}>
                  📅 加日程
                </button>
                <button type="button" className="quick-btn" onClick={() => navigate('/workbench')}>
                  🧰 工作台
                </button>
              </div>
            </div>
          </aside>
        </div>
      </div>

      <CityDrawer
        city={selectedCity}
        points={allPoints}
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
