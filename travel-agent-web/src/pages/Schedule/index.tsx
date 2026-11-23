import { useEffect, useState } from 'react';
import { App, Button, DatePicker, Input, Select, Spin, TimePicker } from 'antd';
import { CalendarOutlined, CheckOutlined, DeleteOutlined, PlusOutlined } from '@ant-design/icons';
import dayjs, { Dayjs } from 'dayjs';
import AppShell from '../../components/AppShell';
import EmptyState from '../../components/EmptyState';
import {
  addSchedule,
  deleteSchedule,
  listSchedule,
  toggleSchedule,
  type ScheduleItem,
} from '../../api/assistant';

/** 日程模块：日程规划与管理 */
export default function Schedule() {
  const { message } = App.useApp();
  const [items, setItems] = useState<ScheduleItem[]>([]);
  const [loading, setLoading] = useState(true);
  const [submitting, setSubmitting] = useState(false);
  /** toggle 请求在途的行：toggle 是取反语义，连点两次会互相抵消，必须挡住并发 */
  const [pending, setPending] = useState<Set<number>>(new Set());
  const [title, setTitle] = useState('');
  const [date, setDate] = useState<Dayjs>(dayjs());
  const [time, setTime] = useState<Dayjs | null>(null);
  const [recurrence, setRecurrence] = useState('NONE');
  const [repeatUntil, setRepeatUntil] = useState<Dayjs | null>(null);

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

  useEffect(() => {
    load();
  }, []);

  const today = dayjs().format('YYYY-MM-DD');
  /** 重复日程按"下一次发生日期"参与今天/待办的划分 */
  const effectiveDate = (s: ScheduleItem) => s.nextOccurrence ?? s.date;
  const sorted = items
    .slice()
    .sort((a, b) =>
      (effectiveDate(a) + (a.time ?? '')).localeCompare(effectiveDate(b) + (b.time ?? '')),
    );
  const todayItems = sorted.filter((s) => effectiveDate(s) === today && !s.done);
  const upcoming = sorted.filter((s) => effectiveDate(s) !== today || s.done);
  const openCount = sorted.filter((s) => !s.done).length;

  const add = async () => {
    if (!title.trim()) {
      message.warning('填写日程事项');
      return;
    }
    setSubmitting(true);
    try {
      await addSchedule({
        title: title.trim(),
        date: date.format('YYYY-MM-DD'),
        time: time ? time.format('HH:mm') : undefined,
        recurrence,
        repeatUntil:
          recurrence !== 'NONE' && repeatUntil ? repeatUntil.format('YYYY-MM-DD') : undefined,
      });
      message.success('已加入日程');
      setTitle('');
      setTime(null);
      setRecurrence('NONE');
      setRepeatUntil(null);
      load();
    } catch (e: any) {
      message.error(e.message);
    } finally {
      setSubmitting(false);
    }
  };

  const toggle = async (id: number) => {
    if (pending.has(id)) return;
    setPending((prev) => new Set(prev).add(id));
    try {
      await toggleSchedule(id);
      load();
    } catch (e: any) {
      message.error(e.message);
    } finally {
      setPending((prev) => {
        const next = new Set(prev);
        next.delete(id);
        return next;
      });
    }
  };

  const remove = async (id: number) => {
    try {
      await deleteSchedule(id);
      message.success('已删除');
      load();
    } catch (e: any) {
      message.error(e.message);
    }
  };

  const renderRow = (s: ScheduleItem, highlight?: boolean) => (
    <div className={`memo-row ${s.done ? 'done' : ''}`} key={s.id}>
      <button
        className={`memo-check ${s.done ? 'checked' : ''}`}
        onClick={() => toggle(s.id)}
        disabled={pending.has(s.id)}
        title={
          s.done ? '恢复' : s.recurrence && s.recurrence !== 'NONE' ? '结束重复' : '标记为完成'
        }
      >
        {s.done && <CheckOutlined />}
      </button>
      {highlight && (
        <span className="mini-tag outdoor" style={{ flexShrink: 0 }}>
          今天
        </span>
      )}
      <span
        style={{
          fontSize: 12,
          color: 'var(--ink-3)',
          flexShrink: 0,
          fontVariantNumeric: 'tabular-nums',
        }}
      >
        {effectiveDate(s).slice(5)}
        {s.time ? ` ${s.time.slice(0, 5)}` : ''}
      </span>
      <span className="memo-content">{s.title}</span>
      {s.recurrence && s.recurrence !== 'NONE' && (
        <span className="mini-tag" style={{ flexShrink: 0 }}>
          {{ DAILY: '每天', WEEKLY: '每周', MONTHLY: '每月' }[s.recurrence] ?? s.recurrence}
          {s.repeatUntil ? ` 至 ${s.repeatUntil.slice(5)}` : ''}
        </span>
      )}
      <Button
        type="text"
        size="small"
        danger
        icon={<DeleteOutlined />}
        onClick={() => remove(s.id)}
      />
    </div>
  );

  return (
    <AppShell active="schedule">
      <div className="dash-wrap">
        <div className="dash-greeting">
          <div>
            <h1>
              <CalendarOutlined aria-hidden="true" className="page-title-icon" /> 日程
            </h1>
            <div className="dg-date">
              {openCount} 项待完成 · 也可以对助理说「明天下午3点开评审会」自动加入日程
            </div>
          </div>
        </div>

        <div className="search-card fade-up" style={{ marginBottom: 16 }}>
          <div
            style={{
              display: 'flex',
              gap: 8,
              flexWrap: 'wrap',
              alignItems: 'center',
              position: 'relative',
              zIndex: 1,
            }}
          >
            <Input
              size="large"
              style={{ flex: 2, minWidth: 200 }}
              placeholder="日程事项，例如：开评审会"
              value={title}
              onChange={(e) => setTitle(e.target.value)}
              onPressEnter={add}
              maxLength={100}
            />
            <DatePicker
              size="large"
              value={date}
              onChange={(d) => d && setDate(d)}
              allowClear={false}
            />
            <TimePicker
              size="large"
              value={time}
              onChange={(t) => setTime(t)}
              format="HH:mm"
              placeholder="时间(可选)"
            />
            <Select
              size="large"
              value={recurrence}
              onChange={setRecurrence}
              style={{ width: 110 }}
              options={[
                { value: 'NONE', label: '不重复' },
                { value: 'DAILY', label: '每天' },
                { value: 'WEEKLY', label: '每周' },
                { value: 'MONTHLY', label: '每月' },
              ]}
            />
            {recurrence !== 'NONE' && (
              <DatePicker
                size="large"
                value={repeatUntil}
                onChange={setRepeatUntil}
                placeholder="结束日期(可选)"
              />
            )}
            <Button
              size="large"
              type="primary"
              icon={<PlusOutlined />}
              onClick={add}
              loading={submitting}
            >
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
                <div className="section-head">
                  <h3>今天</h3>
                </div>
                <div className="inset-list fade-up">
                  {todayItems.map((s) => renderRow(s, true))}
                </div>
              </>
            )}
            <div className="section-head">
              <h3>全部日程</h3>
            </div>
            <div className="inset-list fade-up">
              {upcoming.length === 0 && todayItems.length === 0 ? (
                <EmptyState
                  testId="schedule-empty"
                  icon={<CalendarOutlined aria-hidden="true" />}
                  title="日程是空的，添加第一项吧"
                  hint="用上方输入框添加，或对助理说「明天下午3点开评审会」，到点会提醒你。"
                />
              ) : upcoming.length === 0 ? (
                <EmptyState title="其余日期暂无日程" hint="今天以后暂时没有安排。" />
              ) : (
                upcoming.map((s) => renderRow(s, effectiveDate(s) === today))
              )}
            </div>
          </>
        )}
      </div>
    </AppShell>
  );
}
