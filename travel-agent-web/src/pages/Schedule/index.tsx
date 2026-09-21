import { useEffect, useState } from 'react';
import { App, Button, DatePicker, Empty, Input, Spin, TimePicker } from 'antd';
import { CheckOutlined, DeleteOutlined, PlusOutlined } from '@ant-design/icons';
import dayjs, { Dayjs } from 'dayjs';
import AppShell from '../../components/AppShell';
import { addSchedule, deleteSchedule, listSchedule, toggleSchedule, type ScheduleItem } from '../../api/assistant';

/** 日程模块：日程规划与管理 */
export default function Schedule() {
  const { message } = App.useApp();
  const [items, setItems] = useState<ScheduleItem[]>([]);
  const [loading, setLoading] = useState(true);
  const [submitting, setSubmitting] = useState(false);
  const [title, setTitle] = useState('');
  const [date, setDate] = useState<Dayjs>(dayjs());
  const [time, setTime] = useState<Dayjs | null>(null);

  const load = async () => {
    setLoading(true);
    try {
      setItems(await listSchedule());
    } catch (e: any) {
      message.error(e.message);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { load(); }, []);

  const today = dayjs().format('YYYY-MM-DD');
  const sorted = items.slice().sort((a, b) => (a.date + (a.time ?? '')).localeCompare(b.date + (b.time ?? '')));
  const todayItems = sorted.filter((s) => s.date === today && !s.done);
  const upcoming = sorted.filter((s) => s.date !== today || s.done);
  const openCount = sorted.filter((s) => !s.done).length;

  const add = async () => {
    if (!title.trim()) { message.warning('填写日程事项'); return; }
    setSubmitting(true);
    try {
      await addSchedule({
        title: title.trim(),
        date: date.format('YYYY-MM-DD'),
        time: time ? time.format('HH:mm') : undefined,
      });
      message.success('已加入日程');
      setTitle(''); setTime(null);
      load();
    } catch (e: any) {
      message.error(e.message);
    } finally {
      setSubmitting(false);
    }
  };

  const toggle = async (id: number) => {
    try { await toggleSchedule(id); load(); } catch (e: any) { message.error(e.message); }
  };

  const remove = async (id: number) => {
    try { await deleteSchedule(id); message.success('已删除'); load(); } catch (e: any) { message.error(e.message); }
  };

  const renderRow = (s: ScheduleItem, highlight?: boolean) => (
    <div className={`memo-row ${s.done ? 'done' : ''}`} key={s.id}>
      <button
        className={`memo-check ${s.done ? 'checked' : ''}`}
        onClick={() => toggle(s.id)}
        title={s.done ? '标记为待办' : '标记为完成'}
      >
        {s.done && <CheckOutlined />}
      </button>
      {highlight && <span className="mini-tag outdoor" style={{ flexShrink: 0 }}>今天</span>}
      <span style={{ fontSize: 12, color: 'var(--ink-3)', flexShrink: 0, fontVariantNumeric: 'tabular-nums' }}>
        {s.date.slice(5)}{s.time ? ` ${s.time.slice(0, 5)}` : ''}
      </span>
      <span className="memo-content">{s.title}</span>
      <Button type="text" size="small" danger icon={<DeleteOutlined />} onClick={() => remove(s.id)} />
    </div>
  );

  return (
    <AppShell active="schedule">
      <div className="dash-wrap">
        <div className="dash-greeting">
          <div>
            <h1>📅 日程</h1>
            <div className="dg-date">
              {openCount} 项待完成 · 也可以对助理说「明天下午3点开评审会」自动加入日程
            </div>
          </div>
        </div>

        <div className="search-card fade-up" style={{ marginBottom: 16 }}>
          <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap', alignItems: 'center', position: 'relative', zIndex: 1 }}>
            <Input
              size="large"
              style={{ flex: 2, minWidth: 200 }}
              placeholder="日程事项，例如：开评审会"
              value={title}
              onChange={(e) => setTitle(e.target.value)}
              onPressEnter={add}
              maxLength={100}
            />
            <DatePicker size="large" value={date} onChange={(d) => d && setDate(d)} allowClear={false} />
            <TimePicker size="large" value={time} onChange={(t) => setTime(t)} format="HH:mm" placeholder="时间(可选)" />
            <Button size="large" type="primary" icon={<PlusOutlined />} onClick={add} loading={submitting}>
              添加日程
            </Button>
          </div>
        </div>

        {loading ? (
          <Spin style={{ display: 'block', margin: '50px auto' }} />
        ) : (
          <>
            {todayItems.length > 0 && (
              <>
                <div className="section-head"><h3>今天</h3></div>
                <div className="inset-list fade-up">{todayItems.map((s) => renderRow(s, true))}</div>
              </>
            )}
            <div className="section-head"><h3>全部日程</h3></div>
            <div className="inset-list fade-up">
              {upcoming.length === 0 && todayItems.length === 0 ? (
                <div style={{ padding: 10 }}>
                  <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="日程是空的，添加第一项吧" />
                </div>
              ) : upcoming.length === 0 ? (
                <div style={{ padding: 10 }}>
                  <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="其余日期暂无日程" />
                </div>
              ) : (
                upcoming.map((s) => renderRow(s, s.date === today))
              )}
            </div>
          </>
        )}
      </div>
    </AppShell>
  );
}
