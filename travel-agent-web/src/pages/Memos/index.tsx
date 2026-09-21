import { useEffect, useState } from 'react';
import { App, Button, Empty, Input } from 'antd';
import { CheckOutlined, DeleteOutlined, PlusOutlined } from '@ant-design/icons';
import AppShell from '../../components/AppShell';
import { addMemo, deleteMemo, getMemos, toggleMemo } from '../../api/memo';
import type { Memo } from '../../types/memo';

/** 备忘模块：个人待办与备忘录 */
export default function Memos() {
  const { message } = App.useApp();
  const [items, setItems] = useState<Memo[]>([]);
  const [content, setContent] = useState('');
  const [loading, setLoading] = useState(false);

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
    try {
      await toggleMemo(id);
      load();
    } catch (e: any) {
      message.error(e.message);
    }
  };

  const remove = async (id: number) => {
    try {
      await deleteMemo(id);
      message.success('已删除');
      load();
    } catch (e: any) {
      message.error(e.message);
    }
  };

  const renderRow = (m: Memo) => (
    <div className={`memo-row ${m.done ? 'done' : ''}`} key={m.id}>
      <button
        className={`memo-check ${m.done ? 'checked' : ''}`}
        onClick={() => toggle(m.id)}
        title={m.done ? '标记为待办' : '标记为完成'}
      >
        {m.done && <CheckOutlined />}
      </button>
      <span className="memo-content">{m.content}</span>
      <span className="memo-time">{m.createdAt?.slice(5, 16) ?? ''}</span>
      <Button type="text" size="small" danger icon={<DeleteOutlined />} onClick={() => remove(m.id)} />
    </div>
  );

  return (
    <AppShell active="memos">
      <div className="dash-wrap">
        <div className="dash-greeting">
          <div>
            <h1>📝 备忘</h1>
            <div className="dg-date">
              待办 {open.length} 项 · 已完成 {done.length} 项 · 也可以对助理说「记一下明天办签证」
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

        <div className="inset-list fade-up">
          {items.length === 0 ? (
            <div style={{ padding: 10 }}>
              <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="备忘录是空的" />
            </div>
          ) : (
            <>
              {open.map(renderRow)}
              {done.length > 0 && open.length > 0 && (
                <div style={{ padding: '6px 16px', fontSize: 11.5, color: 'var(--ink-3)' }}>
                  已完成 {done.length} 项
                </div>
              )}
              {done.map(renderRow)}
            </>
          )}
        </div>
        {loading && <div style={{ textAlign: 'center', color: 'var(--ink-3)', fontSize: 12, marginTop: 8 }}>加载中…</div>}
      </div>
    </AppShell>
  );
}
