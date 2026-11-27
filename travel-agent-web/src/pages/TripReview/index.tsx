import { useEffect, useState } from 'react';
import { Alert, Button, Empty, Progress, Spin, Tag } from 'antd';
import {
  ArrowLeftOutlined,
  CameraOutlined,
  CheckCircleOutlined,
  CompassOutlined,
  EnvironmentOutlined,
  GlobalOutlined,
  HistoryOutlined,
  ReloadOutlined,
  RobotOutlined,
  UnorderedListOutlined,
  WalletOutlined,
} from '@ant-design/icons';
import { useNavigate, useParams } from 'react-router-dom';
import dayjs from 'dayjs';
import AppShell from '../../components/AppShell';
import { getTripReview } from '../../api/trip';
import { photoSrc } from '../../api/photo';
import { useAccessTicket } from '../../api/ticket';
import { itineraryStatusLabel } from '../../types/trip';
import type { ReviewPhoto, TripReview } from '../../types/review';

const PHASE_LABEL: Record<string, string> = {
  UPCOMING: '待出发',
  ONGOING: '进行中',
  PAST: '已结束',
  CANCELLED: '已取消',
};

const EVENT_TYPE_LABEL: Record<string, string> = {
  WEATHER: '天气突变',
  CONGESTION: '交通拥堵',
  CLOSURE: '临时闭馆',
  CHECK_IN: '打卡',
  AGENT_ADJUST: 'Agent 调整',
  MANUAL_EDIT: '手动修改',
};

const EVENT_SOURCE_LABEL: Record<string, string> = {
  weather: '天气服务',
  amap: '高德',
  user: '用户',
  agent: 'Agent',
  system: '系统',
};

const OPERATION_LABEL: Record<string, string> = {
  AGENT_COMMIT: 'Agent 规划提交',
  TRIP_CREATE: '创建行程',
  MANUAL_EDIT: '手动修改',
};

function formatDateTime(value?: string | null): string {
  if (!value) return '—';
  const d = dayjs(value);
  return d.isValid() ? d.format('YYYY-MM-DD HH:mm') : value;
}

function money(value?: number | null): string {
  return `¥${Number(value ?? 0).toFixed(0)}`;
}

/**
 * 旅行回顾（P1）：完成率、计划 vs 实际、到访地点、实际路线、照片、事件与修改历史。
 * 「实际到访」只使用后端 visitedPlaces（visitedAt 口径），不会把计划地点补进来。
 */
export default function TripReviewPage() {
  const { id } = useParams();
  const tripId = Number(id);
  const navigate = useNavigate();
  const [review, setReview] = useState<TripReview | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [brokenPhotos, setBrokenPhotos] = useState<Set<number>>(new Set());
  /** 照片原图必须用 media-read 票据（<img> 无法带 Authorization 头） */
  const ticket = useAccessTicket('media-read');

  const load = () => {
    if (!tripId || Number.isNaN(tripId)) {
      setError('行程 ID 无效');
      setLoading(false);
      return;
    }
    setLoading(true);
    setError(null);
    getTripReview(tripId)
      .then(setReview)
      .catch((e: any) => setError(e?.message || '旅行回顾加载失败'))
      .finally(() => setLoading(false));
  };

  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect -- tripId 变化即重拉回顾数据：既有「依赖变化即取数」写法，收敛到数据层 hook 属另开卡的重构
    load();
    // eslint-disable-next-line react-hooks/exhaustive-deps -- 只按 tripId 重拉：load 每次渲染都会重建，进依赖数组会变成「每次渲染都请求」
  }, [tripId]);

  const percent = review ? Math.max(0, Math.min(100, Math.round(review.completionRate * 100))) : 0;

  return (
    <AppShell active="review">
      <div className="dash-wrap review-page">
        <div className="dash-greeting">
          <div className="dg-left">
            <Button
              type="text"
              icon={<ArrowLeftOutlined />}
              aria-label="返回行程"
              onClick={() => navigate(`/trip/${tripId}`)}
            />
            <div>
              <h1>{review ? `${review.name} · 旅行回顾` : '旅行回顾'}</h1>
              <div className="dg-date">
                {review
                  ? `${review.destination || '目的地待定'} · ${review.startDate ?? '日期待定'}${
                      review.endDate ? ` → ${review.endDate}` : ''
                    } · ${review.totalDays} 天 · ${PHASE_LABEL[review.phase] ?? review.phase}`
                  : '完成率 · 计划与实际 · 到访地点 · 照片与事件'}
              </div>
            </div>
          </div>
          <div className="page-actions">
            <Button icon={<ReloadOutlined />} onClick={load} loading={loading}>
              刷新
            </Button>
            <Button type="primary" onClick={() => navigate(`/trip/${tripId}`)}>
              返回行程
            </Button>
          </div>
        </div>

        {loading && (
          <div
            className="dash-card"
            style={{ display: 'grid', placeItems: 'center', minHeight: 220 }}
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

        {!loading && !error && review && (
          <>
            <section className="dash-card" data-testid="review-completion">
              <div className="dc-head">
                <b>
                  <CheckCircleOutlined aria-hidden="true" /> 完成率
                </b>
                <span className="dg-date">
                  {review.completedItems} / {review.totalItems} 项已完成
                </span>
              </div>
              <div className="review-progress">
                <Progress percent={percent} status={percent === 100 ? 'success' : 'active'} />
                <div className="review-counts">
                  <Tag color="green">已完成 {review.completedItems}</Tag>
                  <Tag color="blue">进行中 {review.inProgressItems}</Tag>
                  <Tag>未开始 {review.pendingItems}</Tag>
                  <Tag color="default">已跳过 {review.skippedItems}</Tag>
                  <span className="review-counts-total">共 {review.totalItems} 项</span>
                </div>
              </div>
              {review.totalItems === 0 && (
                <div className="review-note">还没有行程项，先去行程页添加安排或让 AI 规划。</div>
              )}
            </section>

            <div className="review-grid">
              <section className="dash-card" data-testid="review-cost">
                <div className="dc-head">
                  <b>
                    <WalletOutlined aria-hidden="true" /> 计划成本 vs 实际支出
                  </b>
                  <span className="dg-date">实际支出以记账为准</span>
                </div>
                <div className="review-cost-grid">
                  <div className="review-cost-item">
                    <span className="rc-label">计划成本</span>
                    <b>{money(review.plannedCost)}</b>
                  </div>
                  <div className="review-cost-item">
                    <span className="rc-label">实际支出</span>
                    <b>{money(review.actualCost)}</b>
                  </div>
                  <div className="review-cost-item">
                    <span className="rc-label">行程预算</span>
                    <b>{review.budget == null ? '—' : money(review.budget)}</b>
                  </div>
                  <div className="review-cost-item">
                    <span className="rc-label">实际 - 计划</span>
                    <b
                      className={
                        review.variance > 0 ? 'cost-over' : review.variance < 0 ? 'cost-under' : ''
                      }
                    >
                      {review.variance > 0 ? '+' : review.variance < 0 ? '-' : ''}
                      {money(Math.abs(review.variance))}
                    </b>
                  </div>
                </div>
                {review.overBudget && (
                  <Alert
                    type="warning"
                    showIcon
                    style={{ marginTop: 12 }}
                    message="实际支出已超出预算"
                    description={`预算 ${money(review.budget)}，当前实际 ${money(review.actualCost)}。`}
                  />
                )}
              </section>

              <section className="dash-card" data-testid="review-places-summary">
                <div className="dc-head">
                  <b>
                    <EnvironmentOutlined aria-hidden="true" /> 到访概览
                  </b>
                  <span className="dg-date">计划地点与实际到访分开统计</span>
                </div>
                <div className="review-cost-grid">
                  <div className="review-cost-item">
                    <span className="rc-label">计划地点</span>
                    <b>{review.plannedPlaces.length}</b>
                  </div>
                  <div className="review-cost-item">
                    <span className="rc-label">实际到访</span>
                    <b>{review.visitedPlaces.length}</b>
                  </div>
                  <div className="review-cost-item">
                    <span className="rc-label">照片</span>
                    <b>{review.photoCount}</b>
                  </div>
                  <div className="review-cost-item">
                    <span className="rc-label">关联备忘 / 日程</span>
                    <b>
                      {review.memoCount} / {review.scheduleCount}
                    </b>
                  </div>
                </div>
              </section>
            </div>

            <div className="review-grid">
              <section className="dash-card" data-testid="review-planned-places">
                <div className="dc-head">
                  <b>
                    <GlobalOutlined aria-hidden="true" /> 计划地点
                  </b>
                  <span className="dg-date">{review.plannedPlaces.length} 个</span>
                </div>
                {review.plannedPlaces.length === 0 ? (
                  <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="没有带坐标的计划地点" />
                ) : (
                  <div className="review-place-list">
                    {review.plannedPlaces.map((place, index) => (
                      <div className="review-place" key={`${place.itemId ?? index}-${place.title}`}>
                        <Tag>{place.dayIndex != null ? `Day ${place.dayIndex}` : '未分天'}</Tag>
                        <span className="rp-title">{place.title}</span>
                        <span className={`rp-status ${place.visitedAt ? 'visited' : ''}`}>
                          {place.visitedAt ? '已到访' : itineraryStatusLabel(place.status)}
                        </span>
                        {place.visitedAt && (
                          <span className="rp-meta">到访 {formatDateTime(place.visitedAt)}</span>
                        )}
                      </div>
                    ))}
                  </div>
                )}
              </section>

              <section className="dash-card" data-testid="review-visited-places">
                <div className="dc-head">
                  <b>
                    <CheckCircleOutlined aria-hidden="true" /> 实际到访
                  </b>
                  <span className="dg-date">只认真实打卡</span>
                </div>
                {review.visitedPlaces.length === 0 ? (
                  <Empty
                    image={Empty.PRESENTED_IMAGE_SIMPLE}
                    description="还没有实际打卡，完成行程项后这里才会有记录"
                  />
                ) : (
                  <div className="review-place-list">
                    {review.visitedPlaces.map((place, index) => (
                      <div
                        className="review-place visited"
                        key={`${place.itemId ?? index}-${place.title}`}
                      >
                        <Tag color="green">
                          {place.dayIndex != null ? `Day ${place.dayIndex}` : '未分天'}
                        </Tag>
                        <span className="rp-title">{place.title}</span>
                        <span className="rp-meta">到访 {formatDateTime(place.visitedAt)}</span>
                      </div>
                    ))}
                  </div>
                )}
              </section>
            </div>

            <section className="dash-card" data-testid="review-actual-route">
              <div className="dc-head">
                <b>
                  <CompassOutlined aria-hidden="true" /> 实际路线
                </b>
                <span className="dg-date">按到访时间排序 · 只含真实到访且有坐标的地点</span>
              </div>
              {review.actualRoute.length === 0 ? (
                <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="还没有可还原的实际路线" />
              ) : (
                <div className="review-route">
                  {review.actualRoute.map((stop, index) => (
                    <div
                      className="review-route-stop"
                      key={`${stop.itemId ?? index}-${stop.visitedAt}`}
                    >
                      <span className="rr-index">{index + 1}</span>
                      <span className="rr-title">{stop.title}</span>
                      <span className="rr-time">{formatDateTime(stop.visitedAt)}</span>
                      <span className="rr-coord">
                        {stop.latitude != null && stop.longitude != null
                          ? `${Number(stop.latitude).toFixed(4)}, ${Number(stop.longitude).toFixed(4)}`
                          : '无坐标'}
                      </span>
                    </div>
                  ))}
                </div>
              )}
            </section>

            <section className="dash-card" data-testid="review-photos">
              <div className="dc-head">
                <b>
                  <CameraOutlined aria-hidden="true" /> 旅行照片
                </b>
                <span className="dg-date">
                  共 {review.photoCount} 张
                  {review.photos.length < review.photoCount
                    ? ` · 展示前 ${review.photos.length} 张`
                    : ''}
                </span>
              </div>
              {review.photos.length === 0 ? (
                <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="这次旅行还没有照片" />
              ) : !ticket ? (
                <div className="review-photo-loading">
                  <Spin /> <span>正在获取照片访问票据…</span>
                </div>
              ) : (
                <div className="photo-grid">
                  {review.photos.map((photo: ReviewPhoto) =>
                    brokenPhotos.has(photo.id) ? (
                      <a
                        className="photo-broken"
                        key={photo.id}
                        href={photoSrc(photo, ticket)}
                        target="_blank"
                        rel="noreferrer"
                        title={photo.originalName ?? '旅行照片'}
                      >
                        <span>此格式浏览器无法预览</span>
                        <span className="photo-broken-hint">点此打开原图</span>
                      </a>
                    ) : (
                      <div className="photo-item" key={photo.id}>
                        <img
                          src={photoSrc(photo, ticket)}
                          alt={photo.originalName ?? '旅行照片'}
                          loading="lazy"
                          onError={() => setBrokenPhotos((prev) => new Set(prev).add(photo.id))}
                        />
                      </div>
                    ),
                  )}
                </div>
              )}
            </section>

            <div className="review-grid">
              <section className="dash-card" data-testid="review-events">
                <div className="dc-head">
                  <b>
                    <UnorderedListOutlined aria-hidden="true" /> 旅行事件
                  </b>
                  <span className="dg-date">{review.events.length} 条</span>
                </div>
                {review.events.length === 0 ? (
                  <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="还没有旅行事件" />
                ) : (
                  <div className="review-event-list">
                    {review.events.map((event) => (
                      <div className="review-event" key={event.id}>
                        <Tag color="blue">{EVENT_TYPE_LABEL[event.type] ?? event.type}</Tag>
                        <span className="re-summary">{event.summary}</span>
                        <span className="re-meta">
                          {formatDateTime(event.occurredAt)}
                          {event.source
                            ? ` · 来源：${EVENT_SOURCE_LABEL[event.source] ?? event.source}`
                            : ''}
                        </span>
                      </div>
                    ))}
                  </div>
                )}
              </section>

              <section className="dash-card" data-testid="review-modifications">
                <div className="dc-head">
                  <b>
                    <HistoryOutlined aria-hidden="true" /> 修改历史
                  </b>
                  <span className="dg-date">可撤销链的最新记录</span>
                </div>
                {review.modifications.length === 0 ? (
                  <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="还没有修改记录" />
                ) : (
                  <div className="review-event-list">
                    {review.modifications.map((modification) => (
                      <div className="review-event" key={modification.id}>
                        <Tag>
                          {OPERATION_LABEL[modification.operationType] ??
                            modification.operationType}
                        </Tag>
                        <span className="re-summary">{modification.reason || '（无备注）'}</span>
                        <span className="re-meta">{formatDateTime(modification.at)}</span>
                      </div>
                    ))}
                  </div>
                )}
              </section>
            </div>

            <section className="dash-card" data-testid="review-ai-summary">
              <div className="dc-head">
                <b>
                  <RobotOutlined aria-hidden="true" /> AI 旅行总结
                </b>
                <span className="dg-date">基于真实行程数据生成；无模型时为规则文案</span>
              </div>
              {review.aiSummary?.trim() ? (
                <p className="review-ai-summary">{review.aiSummary}</p>
              ) : (
                <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无总结" />
              )}
            </section>
          </>
        )}
      </div>
    </AppShell>
  );
}
