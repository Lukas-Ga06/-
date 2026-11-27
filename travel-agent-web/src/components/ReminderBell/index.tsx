import { useEffect, useRef, useState } from 'react';
import { App, Badge, Button, Drawer, Empty, Tag } from 'antd';
import { BellOutlined, CalendarOutlined, CarryOutOutlined } from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import {
  dismissReminder,
  getReminders,
  type ReminderBoard,
  type ReminderItem,
} from '../../api/reminder';
import { useUIStore } from '../../store/uiStore';
import {
  loadNotified,
  remindersToNotify,
  reminderKey,
  saveNotified,
} from '../../lib/reminderNotify';

/** 前端轮询间隔；提醒本身由服务端按当前时间实时计算 */
const POLL_MS = 60_000;

const NOTIFICATION_SUPPORTED = typeof window !== 'undefined' && 'Notification' in window;

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
  const [permission, setPermission] = useState<NotificationPermission | 'unsupported'>(
    NOTIFICATION_SUPPORTED ? Notification.permission : 'unsupported',
  );
  const permissionRef = useRef(permission);
  const notifiedRef = useRef<Set<string>>(loadNotified());
  useEffect(() => {
    permissionRef.current = permission;
  }, [permission]);

  const load = () =>
    getReminders()
      .then((next) => {
        setBoard(next);
        // 页面在后台时弹系统通知；本次会话内同一条只弹一次
        remindersToNotify(
          next.items,
          notifiedRef.current,
          permissionRef.current,
          document.visibilityState,
        ).forEach((item) => {
          new Notification(item.title, {
            body: `${item.detail} · ${relativeTime(item)}`,
            tag: reminderKey(item),
          });
          notifiedRef.current.add(reminderKey(item));
        });
        saveNotified(notifiedRef.current);
      })
      .catch(() => undefined);

  const enableNotifications = async () => {
    if (!NOTIFICATION_SUPPORTED) {
      message.warning('当前浏览器不支持系统通知');
      return;
    }
    const result = await Notification.requestPermission();
    setPermission(result);
    if (result === 'granted') {
      new Notification('提醒已开启', { body: '页面在后台时也会提醒你日程与出发安排' });
    }
  };

  const sendTestNotification = () => {
    new Notification('测试提醒', { body: '这是一条测试通知，说明浏览器通知已生效' });
  };

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
      <button
        className="sb-link"
        onClick={() => setOpen(true)}
        title={`提醒${count ? `（${count}）` : ''}`}
      >
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
        {NOTIFICATION_SUPPORTED && (
          <div className="notify-row">
            {permission === 'granted' ? (
              <>
                <span>
                  <BellOutlined aria-hidden="true" /> 浏览器通知已开启（页面在后台时提醒）
                </span>
                <Button size="small" type="text" onClick={sendTestNotification}>
                  测试
                </Button>
              </>
            ) : permission === 'default' ? (
              <>
                <span>页面在后台时也能收到提醒</span>
                <Button size="small" type="primary" ghost onClick={enableNotifications}>
                  开启浏览器通知
                </Button>
              </>
            ) : (
              <span>浏览器通知已被拒绝，可在地址栏的站点权限中重新允许</span>
            )}
          </div>
        )}
        {!board || board.items.length === 0 ? (
          <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无提醒" />
        ) : (
          <div className="reminder-list">
            {board.items.map((item) => (
              <div
                className={`reminder-item ${item.overdue ? 'overdue' : ''}`}
                key={`${item.type}-${item.refId}`}
              >
                <div className="ri-icon" aria-hidden="true">
                  {item.type === 'TRIP' ? <CarryOutOutlined /> : <CalendarOutlined />}
                </div>
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
