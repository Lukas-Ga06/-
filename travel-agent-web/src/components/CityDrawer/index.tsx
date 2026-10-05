import { useEffect, useRef, useState } from 'react';
import { App, Button, Drawer, Empty, Spin } from 'antd';
import { CameraOutlined, CloseOutlined, RobotOutlined } from '@ant-design/icons';
import type { TripMapPoint } from '../../types/trip';
import { tripDays, tripRangeLabel } from '../FootprintMap/footprintData';
import { deletePhoto, listPhotos, photoSrc, uploadPhoto, type TripPhoto } from '../../api/photo';
import { useAccessTicket } from '../../api/ticket';

interface Props {
  /** 打开的城市名，null 表示关闭 */
  city: string | null;
  points: TripMapPoint[];
  /** tripId -> 该行程的实际记账金额 */
  spendByTrip: Record<number, number>;
  onClose: () => void;
  onOpenTrip: (tripId: number) => void;
  /** 带着问题（和最近一次行程上下文）跳到 AI 助理 */
  onAskAi: (question: string, tripId?: number) => void;
}

/**
 * 城市足迹详情：该城市的到访记录、计划预算 vs 实际支出、以及一段数据驱动的总结。
 * 总结由本地数据拼装（无需模型）；想深挖可一键跳到 AI 助理。
 */
export default function CityDrawer({ city, points, spendByTrip, onClose, onOpenTrip, onAskAi }: Props) {
  const { message } = App.useApp();
  const fileRef = useRef<HTMLInputElement | null>(null);
  const [photos, setPhotos] = useState<TripPhoto[]>([]);
  const [loadingPhotos, setLoadingPhotos] = useState(false);
  const [uploading, setUploading] = useState(false);
  /** <img> 不能带请求头，原图靠短期只读票据鉴权 */
  const ticket = useAccessTicket();

  const trips = (city
    ? points.filter((p) => ((p.destination ?? p.name ?? '未命名').trim() || '未命名') === city)
    : []
  )
    .slice()
    .sort((a, b) => (b.startDate ?? '').localeCompare(a.startDate ?? ''));

  const days = trips.reduce((sum, p) => sum + tripDays(p), 0);
  const budget = trips.reduce((sum, p) => sum + (p.budget ?? 0), 0);
  const spend = trips.reduce((sum, p) => sum + (spendByTrip[p.tripId] ?? 0), 0);
  const firstVisit = trips.map((p) => p.startDate).filter(Boolean).sort()[0] ?? null;
  const lastVisit = trips.map((p) => p.startDate).filter(Boolean).sort().reverse()[0] ?? null;

  const summary: string[] = [];
  if (city) {
    summary.push(`去过 ${trips.length} 次，累计 ${days} 天。`);
    if (firstVisit && lastVisit) {
      summary.push(firstVisit === lastVisit ? `到访于 ${firstVisit}。` : `最早 ${firstVisit}，最近 ${lastVisit}。`);
    }
    summary.push(`计划预算合计 ¥${budget.toFixed(0)}。`);
    if (spend > 0) {
      const diff = spend - budget;
      summary.push(
        `实际记账 ¥${spend.toFixed(0)}，比计划${diff >= 0 ? '多' : '少'} ¥${Math.abs(diff).toFixed(0)}。`,
      );
    } else {
      summary.push('这些行程还没有记账记录。');
    }
  }

  // 打开城市时汇总该城市所有行程的照片
  useEffect(() => {
    if (!city || trips.length === 0) {
      setPhotos([]);
      return;
    }
    let cancelled = false;
    setLoadingPhotos(true);
    Promise.all(trips.map((trip) => listPhotos(trip.tripId).catch(() => [] as TripPhoto[])))
      .then((groups) => {
        if (!cancelled) setPhotos(groups.flat());
      })
      .finally(() => {
        if (!cancelled) setLoadingPhotos(false);
      });
    return () => {
      cancelled = true;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [city, points]);

  const onPickFiles = async (files: FileList | null) => {
    if (!files || files.length === 0) {
      return;
    }
    const targetTrip = trips[0];
    if (!targetTrip) {
      return;
    }
    setUploading(true);
    try {
      for (const file of Array.from(files)) {
        const saved = await uploadPhoto(targetTrip.tripId, file);
        setPhotos((prev) => [saved, ...prev]);
      }
      message.success('照片已上传');
    } catch (e: any) {
      message.error(e?.message || '上传失败');
    } finally {
      setUploading(false);
      if (fileRef.current) fileRef.current.value = '';
    }
  };

  const removePhoto = async (photo: TripPhoto) => {
    try {
      await deletePhoto(photo.id);
      setPhotos((prev) => prev.filter((p) => p.id !== photo.id));
    } catch (e: any) {
      message.error(e?.message || '删除失败');
    }
  };

  return (
    <Drawer
      title={city ? `🌍 ${city}` : ''}
      open={!!city}
      onClose={onClose}
      width={420}
      styles={{ body: { paddingTop: 12 } }}
    >
      {trips.length === 0 ? (
        <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="这个城市还没有足迹" />
      ) : (
        <>
          <div className="city-stats">
            <div className="city-stat">
              <span className="cs-num">{trips.length}</span>
              <span className="cs-label">次到访</span>
            </div>
            <div className="city-stat">
              <span className="cs-num">{days}</span>
              <span className="cs-label">天</span>
            </div>
            <div className="city-stat">
              <span className="cs-num">¥{budget.toFixed(0)}</span>
              <span className="cs-label">计划预算</span>
            </div>
            <div className="city-stat">
              <span className={`cs-num ${spend > budget && budget > 0 ? 'over' : ''}`}>¥{spend.toFixed(0)}</span>
              <span className="cs-label">实际支出</span>
            </div>
          </div>

          <div className="city-summary">
            <div className="cs-title">✦ 足迹小结</div>
            <p>{summary.join('')}</p>
            <Button
              size="small"
              icon={<RobotOutlined />}
              onClick={() =>
                onAskAi(`帮我总结一下我在${city}的旅行，包括行程安排和花费`, trips[0]?.tripId)
              }
            >
              让 AI 深入总结
            </Button>
          </div>

          <div className="city-trips">
            <div className="cs-title">到访记录</div>
            {trips.map((trip) => {
              const tripSpend = spendByTrip[trip.tripId] ?? 0;
              return (
                <button
                  type="button"
                  key={trip.tripId}
                  className="city-trip"
                  onClick={() => onOpenTrip(trip.tripId)}
                >
                  <span className="ct-main">
                    <b>{trip.name || trip.destination || '未命名行程'}</b>
                    <span>
                      {tripRangeLabel(trip)} · {tripDays(trip)} 天
                    </span>
                  </span>
                  <span className="ct-right">
                    <span className="ct-budget">¥{(trip.budget ?? 0).toFixed(0)}</span>
                    {tripSpend > 0 ? <span className="ct-spend">实付 ¥{tripSpend.toFixed(0)}</span> : null}
                  </span>
                </button>
              );
            })}
          </div>

          <div className="city-photos">
            <div className="cs-title">
              照片
              <Button
                size="small"
                type="text"
                icon={<CameraOutlined />}
                loading={uploading}
                onClick={() => fileRef.current?.click()}
              >
                上传
              </Button>
            </div>
            <input
              ref={fileRef}
              type="file"
              accept="image/*"
              multiple
              hidden
              onChange={(e) => onPickFiles(e.target.files)}
            />
            {loadingPhotos ? (
              <div className="photo-empty">
                <Spin size="small" />
              </div>
            ) : photos.length === 0 ? (
              <div className="photo-empty">
                还没有照片，上传几张记录这次旅行吧（自动关联到最近一次到访）
              </div>
            ) : (
              <div className="photo-grid">
                {photos.map((photo) => (
                  <div className="photo-item" key={photo.id}>
                    <img
                      src={ticket ? photoSrc(photo, ticket) : undefined}
                      alt={photo.originalName ?? '旅行照片'}
                      loading="lazy"
                    />
                    <button
                      type="button"
                      className="photo-del"
                      title="删除照片"
                      onClick={() => removePhoto(photo)}
                    >
                      <CloseOutlined />
                    </button>
                  </div>
                ))}
              </div>
            )}
          </div>
        </>
      )}
    </Drawer>
  );
}
