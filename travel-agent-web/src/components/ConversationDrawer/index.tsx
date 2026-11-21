import { useCallback, useEffect, useState } from 'react';
import { App, Button, Drawer, Skeleton } from 'antd';
import { PlusOutlined, ReloadOutlined } from '@ant-design/icons';
import dayjs from 'dayjs';
import EmptyState from '../EmptyState';
import {
  getConversationMessages,
  listConversations,
  type ConversationSummary,
} from '../../api/agent';
import { useAgentStore } from '../../store/agentStore';
import './conversation-drawer.css';

interface Props {
  open: boolean;
  onClose: () => void;
}

/** 会话时间：今天只显示时刻，更早显示月日，避免列表里出现一长串完整时间戳 */
function formatWhen(iso?: string | null): string {
  if (!iso) return '时间未知';
  const value = dayjs(iso);
  if (!value.isValid()) return '时间未知';
  return value.isSame(dayjs(), 'day') ? value.format('HH:mm') : value.format('MM-DD HH:mm');
}

/**
 * 最近会话抽屉（体验方案 §8.6）。
 *
 * 只做两件事：**继续对话** 与 **开新对话**。「继续」= 取回该会话最近的若干条消息，
 * 装进当前作用域并切到它的 conversationId，后续消息接着同一段服务端记忆走。
 *
 * 边界：会话记忆由后端按保留期清理（默认 7 天），这里拿到的就是「还能继续」的会话；
 * 本地不会缓存历史，每次打开都向服务端要，避免列表和服务端真实状态脱节。
 */
export default function ConversationDrawer({ open, onClose }: Props) {
  const { message } = App.useApp();
  const [items, setItems] = useState<ConversationSummary[] | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  /** 正在回填的会话 id：同一时刻只允许点一个，避免两次回填互相覆盖 */
  const [resuming, setResuming] = useState<string | null>(null);
  const currentId = useAgentStore((s) => s.conversationId);
  const resumeConversation = useAgentStore((s) => s.resumeConversation);
  const newConversation = useAgentStore((s) => s.newConversation);

  const load = useCallback(() => {
    setLoading(true);
    setError(null);
    listConversations()
      .then((rows) => setItems(Array.isArray(rows) ? rows : []))
      .catch((e: any) => setError(e?.message || '会话列表加载失败'))
      .finally(() => setLoading(false));
  }, []);

  // 每次打开都重新拉：期间可能在别的标签页聊过，缓存会让列表说谎
  useEffect(() => {
    if (open) load();
  }, [open, load]);

  const onPick = async (item: ConversationSummary) => {
    if (resuming) return;
    setResuming(item.conversationId);
    try {
      const rows = await getConversationMessages(item.conversationId);
      resumeConversation(item.conversationId, Array.isArray(rows) ? rows : []);
      onClose();
    } catch (e: any) {
      // 会话可能刚过期（保留期清理）：不静默失败，提示后让用户刷新列表
      message.error(e?.message || '这段会话暂时打不开，请刷新后重试');
    } finally {
      setResuming(null);
    }
  };

  const onNew = () => {
    newConversation();
    onClose();
  };

  return (
    <Drawer
      title="最近会话"
      open={open}
      onClose={onClose}
      width={380}
      data-testid="conversation-drawer"
      extra={
        <div className="conv-drawer-actions">
          <Button
            type="text"
            size="small"
            icon={<ReloadOutlined />}
            onClick={load}
            aria-label="刷新会话列表"
          />
          <Button type="primary" size="small" icon={<PlusOutlined />} onClick={onNew}>
            新建对话
          </Button>
        </div>
      }
    >
      {loading && <Skeleton active paragraph={{ rows: 4 }} />}

      {!loading && error && (
        <div className="conv-drawer-error" role="alert">
          <div className="ce-text">{error}</div>
          <Button size="small" icon={<ReloadOutlined />} onClick={load}>
            重试
          </Button>
        </div>
      )}

      {!loading && !error && items && items.length === 0 && (
        <EmptyState
          icon="💬"
          title="还没有历史会话"
          hint="发出第一条消息后，会话会自动保存（保留 7 天），之后可以在这里接着聊。"
          testId="conversation-empty"
        />
      )}

      {!loading && !error && items && items.length > 0 && (
        <div className="conv-list">
          {items.map((item) => {
            const active = item.conversationId === currentId;
            return (
              <button
                key={item.conversationId}
                type="button"
                className={active ? 'conv-item active' : 'conv-item'}
                onClick={() => onPick(item)}
                disabled={resuming !== null}
                aria-current={active ? 'true' : undefined}
                data-testid="conversation-item"
              >
                <span className="ci-title">{item.title || '新会话'}</span>
                <span className="ci-meta">
                  {formatWhen(item.lastMessageAt)} · {item.messageCount} 条消息
                  {active ? ' · 当前会话' : ''}
                </span>
                {resuming === item.conversationId && <span className="ci-loading">正在打开…</span>}
              </button>
            );
          })}
        </div>
      )}
    </Drawer>
  );
}
