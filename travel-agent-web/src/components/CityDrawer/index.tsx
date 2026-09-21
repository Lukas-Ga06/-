import { Button, Drawer, Empty } from 'antd';
import { RobotOutlined } from '@ant-design/icons';
import type { TripMapPoint } from '../../types/trip';
import { tripDays, tripRangeLabel } from '../FootprintMap/footprintData';

interface Props {
  /** 打开的城市名，null 表示关闭 */
  city: string | null;
  points: TripMapPoint[];
  /** tripId -> 该行程的实际记账金额 */
  spendByTrip: Record<number, number>;
  onClose: () => void;
  onOpenTrip: (tripId: number) => void;
  /** 带着问题跳到 AI 助理 */
  onAskAi: (question: string) => void;
}

/**
 * 城市足迹详情：该城市的到访记录、计划预算 vs 实际支出、以及一段数据驱动的总结。
 * 总结由本地数据拼装（无需模型）；想深挖可一键跳到 AI 助理。
 */
export default function CityDrawer({ city, points, spendByTrip, onClose, onOpenTrip, onAskAi }: Props) {
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
              onClick={() => onAskAi(`帮我总结一下我在${city}的旅行，包括行程安排和花费`)}
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
        </>
      )}
    </Drawer>
  );
}
