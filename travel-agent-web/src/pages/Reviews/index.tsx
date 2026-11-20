import { useEffect, useState } from 'react';
import { Button, Empty, Spin } from 'antd';
import { ArrowRightOutlined, ReloadOutlined } from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import AppShell from '../../components/AppShell';
import { listTrips } from '../../api/trip';
import { daysOf, tripPhaseClass, tripPhaseLabel, type Trip } from '../../types/trip';

/** 进行中/已结束优先，其次待出发，最后已取消 */
const PHASE_ORDER: Record<string, number> = {
  ONGOING: 0,
  PAST: 1,
  UPCOMING: 2,
  CANCELLED: 3,
};

function phasePriority(phase?: string | null): number {
  return PHASE_ORDER[phase ?? ''] ?? 2;
}

/** 旅行回顾列表：按阶段排序，每条行程给出回顾入口 */
export default function Reviews() {
  const navigate = useNavigate();
  const [trips, setTrips] = useState<Trip[] | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const load = () => {
    setLoading(true);
    setError(null);
    listTrips()
      .then((rows) => setTrips(Array.isArray(rows) ? rows : []))
      .catch((e: any) => setError(e?.message || '行程列表加载失败'))
      .finally(() => setLoading(false));
  };

  useEffect(() => {
    load();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const ordered = (trips ?? [])
    .slice()
    .sort(
      (a, b) =>
        phasePriority(a.phase) - phasePriority(b.phase) ||
        (b.startDate ?? '').localeCompare(a.startDate ?? ''),
    );

  return (
    <AppShell active="review">
      <div className="dash-wrap">
        <div className="dash-greeting">
          <div>
            <h1>📖 旅行回顾</h1>
            <div className="dg-date">
              行后复盘：完成率、计划与实际花费、真实到访、实际路线、照片与事件
            </div>
          </div>
          <div className="page-actions">
            <Button icon={<ReloadOutlined />} onClick={load} loading={loading}>
              刷新
            </Button>
          </div>
        </div>

        {loading && (
          <div
            className="dash-card"
            style={{ display: 'grid', placeItems: 'center', minHeight: 200 }}
          >
            <Spin size="large" />
          </div>
        )}

        {!loading && error && (
          <div className="dash-card review-state">
            <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={error} />
            <Button onClick={load}>重试</Button>
          </div>
        )}

        {!loading && !error && ordered.length === 0 && (
          <div className="dash-card review-state">
            <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="还没有可回顾的行程" />
            <Button type="primary" onClick={() => navigate('/travel')}>
              去规划一次旅行
            </Button>
          </div>
        )}

        {!loading && !error && ordered.length > 0 && (
          <section className="dash-card" data-testid="reviews-list">
            <div className="dc-head">
              <b>全部行程</b>
              <span className="dg-date">{ordered.length} 个 · 进行中/已结束优先</span>
            </div>
            <div className="review-trip-list">
              {ordered.map((trip) => (
                <div
                  className="review-trip-row"
                  key={trip.id}
                  data-testid={`review-trip-${trip.id}`}
                >
                  <div className="review-trip-main">
                    <div className="review-trip-title">
                      <b>{trip.destination || trip.name || '未命名行程'}</b>
                      <span className={`mini-tag ${tripPhaseClass(trip)}`}>
                        {tripPhaseLabel(trip)}
                      </span>
                    </div>
                    <div className="review-trip-meta">
                      {trip.name}
                      {trip.startDate ? ` · ${trip.startDate} → ${trip.endDate}` : ''}
                      {' · '}
                      {daysOf(trip)} 天
                      {trip.budget != null ? ` · 预算 ¥${Number(trip.budget).toFixed(0)}` : ''}
                    </div>
                  </div>
                  <div className="review-trip-actions">
                    <Button onClick={() => navigate(`/trip/${trip.id}`)}>行程</Button>
                    <Button
                      type="primary"
                      ghost
                      icon={<ArrowRightOutlined />}
                      onClick={() => navigate(`/trip/${trip.id}/review`)}
                    >
                      查看回顾
                    </Button>
                  </div>
                </div>
              ))}
            </div>
          </section>
        )}
      </div>
    </AppShell>
  );
}
