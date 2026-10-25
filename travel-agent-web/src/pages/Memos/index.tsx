import { useEffect, useState } from 'react';
import { App, Button, Empty, Input, Spin } from 'antd';
import { CheckOutlined, DeleteOutlined, PlusOutlined } from '@ant-design/icons';
import AppShell from '../../components/AppShell';
import SwipeRow from '../../components/SwipeRow';
import Skeleton from '../../components/Skeleton';
import { addMemo, deleteMemo, getMemos, toggleMemo } from '../../api/memo';
import type { Memo } from '../../types/memo';

/** 备忘模块：个人待办与备忘录，支持滑动操作 */
export default function Memos() {
  const { message } = App.useApp();
  const [items, setItems] = useState<Memo[]>([]);
  const [content, setContent] = useState('');
  const [loading, setLoading] = useState(true);
  const [exiting, setExiting] = useState<Set<number>>(new Set());
  /** toggle 请求在途的行：toggle 是取反语义，连点两次会互相抵消，必须挡住并发 */
  const [pending, setPending] = useState<Set<number>>(new Set());

  const load = async () => {
    setLoading(true);
    try {
      setItems(await getMemos());
    } catch (e: any) {
      message.error(e.message);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    load();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const open = items.filter((m) => !m.done);
  const done = items.filter((m) => m.done);

  const add = async () => {
    const value = content.trim();
    if (!value) {
      message.warning('写点内容吧');
      return;
    }
    try {
      await addMemo(value);
      setContent('');
      message.success('已添加备忘');
      load();
    } catch (e: any) {
      message.error(e.message);
    }
  };

  const toggle = async (id: number) => {
    if (pending.has(id)) return;
    setPending((prev) => new Set(prev).add(id));
    try {
      await toggleMemo(id);
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
    if (exiting.has(id)) return; // 退出动画已排程，避免重复发删除请求
    // 先播放退出动画，再实际删除
    setExiting((prev) => new Set(prev).add(id));
    setTimeout(async () => {
      try {
        await deleteMemo(id);
        message.success('已删除');
        load();
      } catch (e: any) {
        message.error(e.message);
      } finally {
        setExiting((prev) => {
          const next = new Set(prev);
          next.delete(id);
          return next;
        });
      }
    }, 280);
  };

  const renderRow = (m: Memo, index: number) => {
    const isExiting = exiting.has(m.id);
    return (
      <div
        key={m.id}
        className={`list-enter ${isExiting ? 'list-exit' : ''}`}
        style={{ animationDelay: `${Math.min(index * 0.04, 0.3)}s` }}
      >
        <SwipeRow
          rightActions={[
            {
              label: '删除',
              bg: 'var(--danger)',
              onClick: () => remove(m.id),
            },
            !m.done
              ? {
                  label: '完成',
                  bg: 'var(--ok)',
                  onClick: () => toggle(m.id),
                }
              : {
                  label: '撤销',
                  bg: 'var(--accent)',
                  onClick: () => toggle(m.id),
                },
          ].filter(Boolean) as { label: string; bg: string; onClick: () => void }[]}
          threshold={70}
          maxSlide={150}
        >
          <div className={`memo-row ${m.done ? 'done' : ''}`}>
            <button
              className={`memo-check ${m.done ? 'checked' : ''}`}
              onClick={() => toggle(m.id)}
              disabled={pending.has(m.id)}
              title={m.done ? '标记为待办' : '标记为完成'}
            >
              {m.done && <CheckOutlined />}
            </button>
            <span className="memo-content">{m.content}</span>
            <span className="memo-time">{m.createdAt?.slice(5, 16) ?? ''}</span>
            <Button
              type="text"
              size="small"
              danger
              icon={<DeleteOutlined />}
              onClick={() => remove(m.id)}
            />
          </div>
        </SwipeRow>
      </div>
    );
  };

  return (
    <AppShell active="memos">
      <div className="dash-wrap">
        <div className="dash-greeting">
          <div>
            <h1>📝 备忘</h1>
            <div className="dg-date">
              待办 {open.length} 项 · 已完成 {done.length} 项 · 左滑可完成或删除
            </div>
          </div>
        </div>

        <div className="search-card fade-up" style={{ marginBottom: 16 }}>
          <div className="search-row">
            <Input
              size="large"
              value={content}
              onChange={(e) => setContent(e.target.value)}
              placeholder="添加备忘，例如：明天上午办签证"
              onPressEnter={add}
              maxLength={200}
            />
            <Button size="large" type="primary" icon={<PlusOutlined />} onClick={add}>
              添加
            </Button>
          </div>
        </div>

        {loading ? (
          <div className="inset-list" style={{ padding: 8 }}>
            {[0, 1, 2].map((i) => (
              <div key={i} style={{ padding: '10px 16px' }}>
                <Skeleton.ListItem />
              </div>
            ))}
          </div>
        ) : (
          <div className="inset-list fade-up">
            {items.length === 0 ? (
              <div style={{ padding: 10 }}>
                <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="备忘录是空的" />
              </div>
            ) : (
              <>
                {open.map((m, i) => renderRow(m, i))}
                {done.length > 0 && open.length > 0 && (
                  <div style={{ padding: '6px 16px', fontSize: 11.5, color: 'var(--ink-3)' }}>
                    已完成 {done.length} 项
                  </div>
                )}
                {done.map((m, i) => renderRow(m, open.length + i))}
              </>
            )}
          </div>
        )}
      </div>
    </AppShell>
  );
}
