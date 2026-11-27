import { Segmented } from 'antd';
import MapView from '../../../components/MapView';
import Timeline from '../../../components/Timeline';
import ChatPanel from '../../../components/ChatPanel';
import type { AgentFocus } from '../../../types/agent';
import type { ItineraryItem, ItineraryStatus, RouteLeg, Trip } from '../../../types/trip';
import type { SideView } from './types';

interface Props {
  tripId: number;
  currentTrip: Trip | null;
  items: ItineraryItem[];
  routeLegs: RouteLeg[];
  /** Agent 聚焦事件：自动切到对应 Day 并定位到那一条 */
  focus: AgentFocus | null;
  /** 当前选中的 Day（地图只画这一天） */
  selectedDay: number;
  /** 正在提交状态变更的行程项 id */
  statusBusy: number | null;
  /** 离线记录、尚未被服务端确认的条目 id */
  pendingSyncIds: Set<number>;
  onItemStatusChange: (itemId: number, next: ItineraryStatus) => void;
  sideView: SideView;
  onSideViewChange: (view: SideView) => void;
  onOpenDiagnostics: () => void;
}

/**
 * 行程工作区（§8.5）：时间轴做主栏，地图与 AI 共享右侧可切换面板。
 */
export default function TripWorkspace({
  tripId,
  currentTrip,
  items,
  routeLegs,
  focus,
  selectedDay,
  statusBusy,
  pendingSyncIds,
  onItemStatusChange,
  sideView,
  onSideViewChange,
  onOpenDiagnostics,
}: Props) {
  return (
    <div className="trip-workbench">
      <section className="dash-card trip-timeline-card trip-main-col">
        <Timeline
          trip={currentTrip}
          items={items}
          focus={focus}
          routeLegs={routeLegs}
          onItemStatusChange={onItemStatusChange}
          statusBusyId={statusBusy}
          pendingSyncIds={pendingSyncIds}
        />
      </section>
      <aside className="dash-card trip-side-col">
        <Segmented
          size="small"
          block
          data-testid="trip-side-switch"
          value={sideView}
          onChange={(v) => onSideViewChange(v as SideView)}
          options={[
            { value: 'map', label: '地图' },
            { value: 'ai', label: 'AI 助手' },
          ]}
        />
        {/*
          侧栏 keep-alive（§8.5）：地图与 AI 一次挂载、按可见性显隐。
          以前点「AI 助手」会把地图卸载重建，用户缩放/平移到的地方全丢；
          现在只切 visibility，地图实例与相机保持原样。
        */}
        <div className="trip-side-body trip-side-keepalive">
          <div
            className="trip-pane"
            data-testid="trip-side-pane-map"
            data-active={sideView === 'map'}
            aria-hidden={sideView !== 'map'}
          >
            <div className="trip-map-body">
              <MapView
                items={items}
                focus={focus}
                focusDay={selectedDay}
                routeLegs={routeLegs}
                active={sideView === 'map'}
                onViewTimeline={() => {
                  document
                    .querySelector('.trip-timeline-card')
                    ?.scrollIntoView({ behavior: 'smooth', block: 'start' });
                }}
                onOpenDiagnostics={onOpenDiagnostics}
              />
            </div>
          </div>
          <div
            className="trip-pane"
            data-testid="trip-side-pane-ai"
            data-active={sideView === 'ai'}
            aria-hidden={sideView !== 'ai'}
          >
            <div className="trip-chat-body">
              <ChatPanel tripId={tripId} placeholder="例如：明天下雨，把户外景点换掉" />
            </div>
          </div>
        </div>
      </aside>
    </div>
  );
}
