import { useEffect, useState } from 'react';
import { App, Badge, Button, Drawer, Empty, Tag } from 'antd';
import { BellOutlined } from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import { dismissReminder, getReminders, type ReminderBoard, type ReminderItem } from '../../api/reminder';
import { useUIStore } from '../../store/uiStore';

/** 前端轮询间隔；提醒本身由服务端按当前时间实时计算 */
const POLL_MS = 60_000;

function relativeTime(item: ReminderItem): string {
  if (item.overdue) {
    return '已过时间，记得处理';
  }
  if (item.minutesUntil <= 0) {
    return '即将开始';
  }
  if (item.minutesUntil < 60) {
    return `${item.minutesUntil} 分钟后`;
  }
  const hours = Math.floor(item.minutesUntil / 60);
  const minutes = item.minutesUntil % 60;
  return minutes ? `${hours} 小时 ${minutes} 分钟后` : `${hours} 小时后`;
}

/** 侧栏提醒铃铛：日程到点 + 行程出发提醒，支持「知道了」忽略 */
export default function ReminderBell() {
  const navigate = useNavigate();
  const { message } = App.useApp();
  const open = useUIStore((s) => s.remindersOpen);
  const setOpen = useUIStore((s) => s.setRemindersOpen);
  const [board, setBoard] = useState<ReminderBoard | null>(null);
  const [busy, setBusy] = useState(false);

  const load = () =>
    getReminders()
      .then(setBoard)
      .catch(() => undefined);

  useEffect(() => {
    load();
    const timer = window.setInterval(load, POLL_MS);
    return () => window.clearInterval(timer);
  }, []);

  useEffect(() => {
    if (open) load();
  }, [open]);

  const onDismiss = async (item: ReminderItem) => {
    setBusy(true);
    try {
      await dismissReminder(item);
      await load();
    } catch (e: any) {
      message.error(e?.message || '操作失败');
    } finally {
      setBusy(false);
    }
  };

  const go = (item: ReminderItem) => {
    setOpen(false);
    navigate(item.type === 'TRIP' ? `/trip/${item.refId}` : '/travel?tab=schedule');
  };

  const count = board?.count ?? 0;

  return (
    <>
      <button className="sb-link" onClick={() => setOpen(true)} title={`提醒${count ? `（${count}）` : ''}`}>
        <Badge count={count} size="small">
          <BellOutlined />
        </Badge>
        <span className="sb-label">提醒</span>
      </button>

      <Drawer
        title={count ? `提醒（${count}）` : '提醒'}
        open={open}
        onClose={() => setOpen(false)}
        width={400}
        styles={{ body: { paddingTop: 12 } }}
      >
        {!board || board.items.length === 0 ? (
          <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无提醒" />
        ) : (
          <div className="reminder-list">
            {board.items.map((item) => (
              <div
                className={`reminder-item ${item.overdue ? 'overdue' : ''}`}
                key={`${item.type}-${item.refId}`}
              >
                <div className="ri-icon">{item.type === 'TRIP' ? '🧳' : '📅'}</div>
                <div className="ri-main">
                  <div className="ri-title">
                    {item.title}
                    {item.overdue && <Tag color="red">已过期</Tag>}
                  </div>
                  <div className="ri-detail">{item.detail}</div>
                  <div className="ri-time">{relativeTime(item)}</div>
                </div>
                <div className="ri-actions">
                  <Button size="small" type="primary" ghost onClick={() => go(item)}>
                    查看
                  </Button>
                  <Button size="small" type="text" loading={busy} onClick={() => onDismiss(item)}>
                    知道了
                  </Button>
                </div>
              </div>
            ))}
          </div>
        )}
      </Drawer>
    </>
  );
}
