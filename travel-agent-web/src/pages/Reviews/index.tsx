import { useEffect, useState } from 'react';
import { Button, Empty, Spin, Tag } from 'antd';
import {
  ArrowRightOutlined,
  BookOutlined,
  CompassOutlined,
  ReloadOutlined,
} from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import AppShell from '../../components/AppShell';
import EmptyState from '../../components/EmptyState';
import { listTrips } from '../../api/trip';
import { daysOf, tripPhaseClass, tripPhaseLabel, type Trip } from '../../types/trip';
import './reviews.css';

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

/**
 * 旅行回顾列表：按阶段排序，每条行程给出回顾入口。
 *
 * 设计口径（docs/design/2026-11-20 §18 第 9 条）：**未来行程不作为可回顾内容**。
 * 待出发的行程仍在列表里可见（用户要能找到自己订好的行程），但只给「行程」入口、
 * 不给「查看回顾」——它还没有可回顾的实际到访、花费与照片，给了会误导成「已经发生过」。
 */
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
  const reviewable = ordered.filter((trip) => trip.phase !== 'UPCOMING');
  const upcoming = ordered.filter((trip) => trip.phase === 'UPCOMING');
  const isEmpty = !loading && !error && ordered.length === 0;

  return (
    <AppShell active="review">
      <div className="dash-wrap">
        <div className="dash-greeting">
          <div>
            <h1>
              <BookOutlined aria-hidden="true" className="page-title-icon" /> 旅行回顾
            </h1>
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

        {isEmpty && (
          <div className="dash-card review-state">
            <EmptyState
              testId="reviews-empty"
              icon={<CompassOutlined aria-hidden="true" />}
              title="还没有可回顾的行程"
              hint="完成一次旅行后，这里会聚合完成率、计划与实际花费、真实到访和照片。"
              action={{ label: '去规划一次旅行', onClick: () => navigate('/travel') }}
            />
          </div>
        )}

        {!loading && !error && reviewable.length > 0 && (
          <section className="dash-card" data-testid="reviews-list">
            <div className="dc-head">
              <b>可回顾行程</b>
              <span className="dg-date">{reviewable.length} 个 · 进行中/已结束优先</span>
            </div>
            <div className="review-trip-list">
              {reviewable.map((trip) => (
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

        {!loading && !error && upcoming.length > 0 && (
          <section className="dash-card" data-testid="reviews-upcoming" style={{ marginTop: 16 }}>
            <div className="dc-head">
              <b>待出发（尚不可回顾）</b>
              <span className="dg-date">{upcoming.length} 个 · 出发并产生实际到访后再来回顾</span>
            </div>
            <div className="review-trip-list">
              {upcoming.map((trip) => (
                <div
                  className="review-trip-row"
                  key={trip.id}
                  data-testid={`review-trip-${trip.id}`}
                  style={{ background: 'var(--surface-2)' }}
                >
                  <div className="review-trip-main">
                    <div className="review-trip-title">
                      <b>{trip.destination || trip.name || '未命名行程'}</b>
                      <Tag bordered={false}>{tripPhaseLabel(trip)}</Tag>
                    </div>
                    <div className="review-trip-meta">
                      {trip.name}
                      {trip.startDate ? ` · ${trip.startDate} → ${trip.endDate}` : ''}
                      {' · '}
                      {daysOf(trip)} 天
                    </div>
                  </div>
                  <div className="review-trip-actions">
                    <Button onClick={() => navigate(`/trip/${trip.id}`)}>行程</Button>
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
