import { useEffect, useState } from 'react';
import { App, Button, Empty, Spin } from 'antd';
import { ArrowLeftOutlined, EnvironmentOutlined, PlayCircleOutlined, ReloadOutlined } from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import AppShell from '../../components/AppShell';
import FootprintMap from '../../components/FootprintMap';
import { footprintStats, orderedFootprints, tripDays, tripRangeLabel } from '../../components/FootprintMap/footprintData';
import { getTripsMap } from '../../api/trip';
import type { TripMapPoint } from '../../types/trip';

/** 我的足迹：全屏 3D 球体地图 + 行程列表 + 按时间回放 */
export default function FootprintPage() {
  const navigate = useNavigate();
  const { message } = App.useApp();
  const [points, setPoints] = useState<TripMapPoint[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [focusTripId, setFocusTripId] = useState<number | null>(null);
  const [playSignal, setPlaySignal] = useState(0);
  const [touring, setTouring] = useState(false);

  const load = async () => {
    setError(null);
    setPoints(null);
    try {
      setPoints(await getTripsMap());
    } catch (e: any) {
      setError(e?.message || '足迹加载失败');
    }
  };

  useEffect(() => {
    load();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const stats = footprintStats(points ?? []);
  // 列表按时间倒序（最近的旅行在最上面），回放顺序由地图按时间正序处理
  const recentFirst = orderedFootprints(points ?? []).slice().reverse();
  const unlocatedCount = stats.trips - stats.located;

  const flyTo = (tripId: number) => {
    setFocusTripId(null);
    // 先清空再设置，保证连点同一条也能重新定位
    window.requestAnimationFrame(() => setFocusTripId(tripId));
  };

  const replay = () => {
    if (stats.located === 0) {
      message.warning('还没有可定位的行程，先去规划一次旅行吧');
      return;
    }
    setPlaySignal((v) => v + 1);
  };

  return (
    <AppShell active="footprint">
      <div className="fp-page">
        <div className="fp-head">
          <Button type="text" icon={<ArrowLeftOutlined />} onClick={() => navigate('/')} />
          <div className="fp-title">
            <b>🌍 我的足迹</b>
            <span>
              {stats.trips === 0
                ? '每次旅行都会在这里留下一个光点'
                : `去过 ${stats.cities} 个城市 · ${stats.trips} 次旅行 · ${stats.days} 天`}
            </span>
          </div>
          <div className="fp-head-actions">
            <Button icon={<ReloadOutlined />} onClick={load} disabled={points === null && !error}>
              刷新
            </Button>
            <Button
              type="primary"
              icon={<PlayCircleOutlined />}
              onClick={replay}
              disabled={touring || stats.located === 0}
            >
              {touring ? '回放中…' : '回放足迹'}
            </Button>
          </div>
        </div>

        <div className="fp-stats">
          <div className="fp-stat">
            <span className="fs-num">{stats.trips}</span>
            <span className="fs-label">行程</span>
          </div>
          <div className="fp-stat">
            <span className="fs-num">{stats.cities}</span>
            <span className="fs-label">城市</span>
          </div>
          <div className="fp-stat">
            <span className="fs-num">{stats.days}</span>
            <span className="fs-label">旅行天数</span>
          </div>
          <div className="fp-stat">
            <span className="fs-num">¥{stats.budget.toFixed(0)}</span>
            <span className="fs-label">预算合计</span>
          </div>
          {unlocatedCount > 0 && (
            <div className="fp-stat warn">
              <span className="fs-num">{unlocatedCount}</span>
              <span className="fs-label">未定位</span>
            </div>
          )}
        </div>

        <div className="fp-body">
          <aside className="fp-aside">
            <div className="fp-aside-head">时间线 · 由近及远</div>
            {points === null && !error ? (
              <div className="fp-aside-loading">
                <Spin />
              </div>
            ) : recentFirst.length === 0 ? (
              <div className="fp-aside-empty">
                <Empty
                  image={Empty.PRESENTED_IMAGE_SIMPLE}
                  description="还没有足迹"
                />
                <Button type="primary" onClick={() => navigate('/travel')}>
                  去规划第一次旅行
                </Button>
              </div>
            ) : (
              recentFirst.map((point, index) => (
                <div
                  key={point.tripId}
                  className="fp-trip"
                  role="button"
                  tabIndex={0}
                  onClick={() => flyTo(point.tripId)}
                  onKeyDown={(e) => {
                    if (e.key === 'Enter' || e.key === ' ') {
                      e.preventDefault();
                      flyTo(point.tripId);
                    }
                  }}
                  title="在地图上定位"
                >
                  <span className="ft-index">{recentFirst.length - index}</span>
                  <span className="ft-main">
                    <b>{point.destination || point.name || '未命名行程'}</b>
                    <span>
                      {tripRangeLabel(point)} · {tripDays(point)} 天
                    </span>
                  </span>
                  <span className="ft-right">
                    <span className="ft-budget">¥{(point.budget ?? 0).toFixed(0)}</span>
                    <button
                      type="button"
                      className="ft-enter"
                      onClick={(e) => {
                        e.stopPropagation();
                        navigate(`/trip/${point.tripId}`);
                      }}
                      title="进入行程"
                    >
                      <EnvironmentOutlined /> 进入
                    </button>
                  </span>
                </div>
              ))
            )}
          </aside>

          <div className="fp-main">
            {error ? (
              <div className="fp-state">
                <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={error} />
                <Button onClick={load}>重试</Button>
              </div>
            ) : points === null ? (
              <div className="fp-state">
                <Spin size="large" />
                <span className="fp-state-text">正在定位你的足迹…</span>
              </div>
            ) : stats.located === 0 ? (
              <div className="fp-state">
                <Empty
                  image={Empty.PRESENTED_IMAGE_SIMPLE}
                  description="行程目的地还没定位到坐标，创建新行程后这里会亮起光点"
                />
                <Button type="primary" onClick={() => navigate('/travel')}>
                  去规划旅行
                </Button>
              </div>
            ) : (
              <FootprintMap
                points={points}
                onSelect={(tripId) => navigate(`/trip/${tripId}`)}
                focusTripId={focusTripId}
                playSignal={playSignal}
                autoSpin
                onTourStateChange={setTouring}
              />
            )}
          </div>
        </div>
      </div>
    </AppShell>
  );
}
