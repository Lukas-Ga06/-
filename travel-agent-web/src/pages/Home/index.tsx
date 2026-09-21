import { lazy, Suspense, useEffect, useState } from 'react';
import { App, Button, Empty, Input } from 'antd';
import { SendOutlined } from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import dayjs from 'dayjs';
import { getTripsMap, listTrips } from '../../api/trip';
import { getExpenseOverview } from '../../api/expense';
import { getMemos, toggleMemo } from '../../api/memo';
import { healthSummary, listSchedule, type ScheduleItem } from '../../api/assistant';
import { listMinutes, listReports } from '../../api/assistant';
import { listArtifacts } from '../../api/assistant';
import { useAuthStore } from '../../store/authStore';
import AppShell from '../../components/AppShell';
import VoiceButton from '../../components/VoiceButton';
import type { ExpenseOverview } from '../../types/expense';
import type { Memo } from '../../types/memo';
import type { Trip, TripMapPoint } from '../../types/trip';
import type { HealthSummary } from '../../types/assistant';

const EXAMPLE_PROMPT = '帮我规划周末上海两日游';
const QUICK_MODULES = [
  { icon: '📅', label: '行程', path: '/schedule' },
  { icon: '💰', label: '记账', path: '/expenses' },
  { icon: '📝', label: '待办', path: '/memos' },
  { icon: '✨', label: '更多', path: '/office' },
];

/** 总览：个人助理 Dashboard */
export default function Home() {
  const { message } = App.useApp();
  const navigate = useNavigate();
  const user = useAuthStore((s) => s.user);

  const [prompt, setPrompt] = useState('');
  const [trips, setTrips] = useState<Trip[]>([]);
  const [mapPoints, setMapPoints] = useState<TripMapPoint[] | null>(null);
  const [overview, setOverview] = useState<ExpenseOverview | null>(null);
  const [memos, setMemos] = useState<Memo[]>([]);
  const [schedule, setSchedule] = useState<ScheduleItem[]>([]);
  const [aiCount, setAiCount] = useState(0);
  const [health, setHealth] = useState<HealthSummary | null>(null);

  const loadOverview = async () => {
    try {
      setOverview(await getExpenseOverview());
    } catch {
      // 忽略
    }
  };

  useEffect(() => {
    const safe = (p: Promise<any>, fallback: any) => p.catch(() => fallback);
    Promise.all([
      safe(listTrips(), []),
      safe(getTripsMap(), []),
      safe(getExpenseOverview(), null),
      safe(getMemos(), []),
      safe(listSchedule(), []),
      safe(healthSummary(), null),
      safe(listMinutes(), []),
      safe(listReports(), []),
      safe(listArtifacts(), []),
    ]).then(([tr, mp, ov, mm, sc, hl, min, rp, art]) => {
      setTrips(tr);
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
  const healthSteps = health?.latest?.steps ?? 0;

  const send = (value?: string) => {
    const p = (value ?? prompt).trim();
    if (!p) {
      message.warning('告诉我你想完成什么');
      return;
    }
    navigate(`/assistant?q=${encodeURIComponent(p)}`);
    setPrompt('');
  };

  const stats = [
    { icon: '📅', cls: 'blue', label: '今日安排', num: String(todaySchedule.length), sub: '项', path: '/schedule' },
    { icon: '💰', cls: 'orange', label: '本月支出', num: `¥${(overview?.monthSpent ?? 0).toFixed(0)}`, path: '/expenses' },
    { icon: '🤖', cls: 'green', label: 'AI 任务', num: String(aiCount), sub: '项', path: '/learning' },
    { icon: '⏰', cls: 'purple', label: '待提醒', num: String(openMemos.length), sub: '项', path: '/memos' },
  ];

  // AI 今日建议：由真实数据推导
  const suggestions: string[] = [];
  if (openMemos.length > 0) suggestions.push(`发现 ${openMemos.length} 项待处理任务，说「我的备忘」即可查看`);
  if (todaySchedule.length > 0) suggestions.push(`今天有 ${todaySchedule.length} 项日程安排，注意预留通勤时间`);
  const monthSpent = overview?.monthSpent ?? 0;
  if (monthSpent > 0) suggestions.push(`本月已支出 ¥${monthSpent.toFixed(0)}，可在记账模块查看渠道分布`);
  if ((health?.avgSteps ?? 99999) < 6000) suggestions.push('最近步数偏少，建议每天散步 30 分钟');
  if (suggestions.length === 0) suggestions.push('一切井然有序，去规划下一场旅行吧！');

  return (
    <AppShell active="home">
      <div className="dash-wrap">
        <div className="dash-greeting">
          <div>
            <h1>
              {dayjs().hour() < 12 ? '早上好' : dayjs().hour() < 18 ? '下午好' : '晚上好'}，{user?.nickname ?? user?.username ?? '朋友'} 👋
            </h1>
            <div className="dg-date">今天想让我帮你完成什么？</div>
          </div>
        </div>

        {/* ✨ 助手输入 */}
        <div className="assistant-quick lg-sweep fade-up">
          <div style={{ display: 'flex', gap: 10, alignItems: 'center', position: 'relative', zIndex: 1 }}>
            <span style={{ fontSize: 20 }}>✨</span>
            <Input
              size="large"
              value={prompt}
              onChange={(e) => setPrompt(e.target.value)}
              placeholder="告诉我你想完成什么……"
              onPressEnter={() => send()}
              style={{ border: 'none', boxShadow: 'none', background: 'transparent', fontSize: 15 }}
            />
            <VoiceButton onText={(t) => setPrompt(t)} />
            <Button
              type="primary"
              size="large"
              icon={<SendOutlined />}
              onClick={() => send()}
              disabled={!prompt.trim()}
            >
              发送
            </Button>
          </div>
          <div className="aq-hint">
            <span className="suggest-chip" onClick={() => setPrompt(EXAMPLE_PROMPT)}>
              {EXAMPLE_PROMPT}
            </span>
            {QUICK_MODULES.map((m) => (
              <span key={m.label} className="suggest-chip" onClick={() => navigate(m.path)}>
                {m.icon} {m.label}
              </span>
            ))}
          </div>
        </div>

        {/* 四模块统计 */}
        <div className="stat-row">
          {stats.map((s) => (
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

        {/* AI 建议 + 今日日程 */}
        <div className="dash-grid">
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
              <b>📅 今日与即将到来</b>
              <button className="dc-link" onClick={() => navigate('/schedule')}>
                日程模块 →
              </button>
            </div>
            {upcoming.length === 0 ? (
              <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无日程，说「明天下午3点开会」即可添加" />
            ) : (
              upcoming.map((s) => (
                <div key={s.id} className="recent-expense-row" style={{ padding: '7px 4px', cursor: 'default' }}>
                  <span className="mini-tag" style={{ flexShrink: 0 }}>
                    {s.date === today ? '今天' : s.date.slice(5)}
                  </span>
                  <span style={{ flex: 1, fontSize: 13, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                    {s.time ? `${s.time.slice(0, 5)} ` : ''}
                    {s.title}
                  </span>
                </div>
              ))
            )}
          </div>
        </div>

        {/* 足迹地图 */}
        <div className="dash-card span2 lg-sweep" style={{ marginTop: 2 }}>
          <div className="dc-head">
            <b>🗺️ 我的足迹</b>
            <span style={{ fontSize: 12, color: 'var(--ink-3)' }}>
              {mapPoints ? `${mapPoints.filter((p) => p.lat != null).length}/${mapPoints.length} 个行程已定位 · 点击标记进入` : '定位中…'}
            </span>
          </div>
          <div className="footprint-map-wrap" style={{ height: 340 }}>
            {mapPoints === null ? (
              <div className="footprint-loading">正在定位目的地…</div>
            ) : mapPoints.length === 0 ? (
              <div className="footprint-loading">还没有行程，去「旅行规划」创建一个吧</div>
            ) : (
              <Suspense fallback={<div className="footprint-loading">地图加载中…</div>}>
                <FootprintMap points={mapPoints} onSelect={(id) => navigate(`/trip/${id}`)} />
              </Suspense>
            )}
          </div>
        </div>
      </div>
    </AppShell>
  );
}
// 首页足迹地图依赖 maplibre-gl（约 1MB），懒加载避免拖慢首屏
const FootprintMap = lazy(() => import('../../components/FootprintMap'));
