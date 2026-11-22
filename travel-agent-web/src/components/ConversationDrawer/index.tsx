import { useCallback, useEffect, useState } from 'react';
import { App, Button, Drawer, Input, Popconfirm, Skeleton } from 'antd';
import { DeleteOutlined, EditOutlined, PlusOutlined, ReloadOutlined } from '@ant-design/icons';
import dayjs from 'dayjs';
import EmptyState from '../EmptyState';
import {
  deleteConversation,
  getConversationMessages,
  listConversations,
  renameConversation,
  type ConversationSummary,
} from '../../api/agent';
import { tripIdOfScope, useAgentStore } from '../../store/agentStore';
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

/** 会话作用域的中文标签：让用户一眼看出这段对话是在哪儿聊的（V21） */
function scopeLabel(scope?: string | null): string | null {
  if (!scope) return null;
  if (scope === 'planner') return '规划';
  if (tripIdOfScope(scope) !== null) return `行程 #${tripIdOfScope(scope)}`;
  if (scope === 'assistant') return '助手';
  return scope;
}

/** 标题输入上限：与服务端的截断口径一致（超出部分会变成省略号） */
const TITLE_MAX_LENGTH = 40;

/**
 * 最近会话抽屉（体验方案 §8.6 / V21 作用域 / V23 管理）。
 *
 * 三件事：**继续对话**、**开新对话**、**管理单段会话**（改名 / 删除）。
 * 「继续」= 取回该会话最近的若干条消息，装进当前作用域并切到它的 conversationId，
 * 后续消息接着同一段服务端记忆走。
 *
 * V21：续聊会连带恢复会话的作用域 —— 一段绑定在行程上的会话，在助手页继续聊时
 * 下一条消息仍然带 tripId（store 的 resumedTripId），不再「只恢复消息、不恢复绑定」。
 *
 * V23：改名与删除都会重新拉一次列表 —— 标题可能被服务端截断/折叠空白、
 * 删除后条数与顺序都会变；让服务端说什么是最终结果，比在前端拼一份「差不多对」的副本可靠。
 * 删掉的是当前正在聊的那段时，顺手开一段新会话，避免客户端继续往一段已经不存在的历史里追加。
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
  /** 正在改名的会话 id（内联编辑，同时只开一个） */
  const [editingId, setEditingId] = useState<string | null>(null);
  /** 改名/删除进行中：期间禁用该行动作，避免重复提交 */
  const [busyId, setBusyId] = useState<string | null>(null);
  const [draftTitle, setDraftTitle] = useState('');
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
    if (resuming || editingId) return;
    setResuming(item.conversationId);
    try {
      const resumed = await getConversationMessages(item.conversationId);
      const rows = Array.isArray(resumed?.messages) ? resumed.messages : [];
      // 作用域以服务端记录为准（它比列表里的副本更新：期间可能在别的标签页聊过）
      const scope = resumed?.scope ?? item.scope ?? null;
      resumeConversation(item.conversationId, rows, scope);
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

  const startRename = (item: ConversationSummary) => {
    setEditingId(item.conversationId);
    // 预填当前标题：用户多半只想改其中几个字
    setDraftTitle(item.title ?? '');
  };

  const cancelRename = () => {
    setEditingId(null);
    setDraftTitle('');
  };

  const submitRename = async (item: ConversationSummary) => {
    if (busyId) return;
    const next = draftTitle.trim();
    setBusyId(item.conversationId);
    try {
      // 空标题 = 恢复自动标题（服务端删掉覆盖行），所以允许提交空串
      await renameConversation(item.conversationId, next);
      cancelRename();
      message.success(next ? '已重命名' : '已恢复自动标题');
      load();
    } catch (e: any) {
      message.error(e?.message || '重命名失败，请重试');
    } finally {
      setBusyId(null);
    }
  };

  const onDelete = async (item: ConversationSummary) => {
    if (busyId) return;
    setBusyId(item.conversationId);
    try {
      await deleteConversation(item.conversationId);
      if (editingId === item.conversationId) {
        cancelRename();
      }
      // 删的是当前正在聊的那段：换一个新会话，别让客户端继续往已经不存在的历史里追加
      if (currentId === item.conversationId) {
        newConversation();
      }
      message.success('会话已删除');
      load();
    } catch (e: any) {
      message.error(e?.message || '删除失败，请重试');
    } finally {
      setBusyId(null);
    }
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
            const editing = editingId === item.conversationId;
            const busy = busyId === item.conversationId;
            return (
              <div
                key={item.conversationId}
                className={active ? 'conv-item active' : 'conv-item'}
                data-busy={busy ? 'true' : undefined}
              >
                <button
                  type="button"
                  className="ci-main"
                  onClick={() => onPick(item)}
                  disabled={resuming !== null || editing}
                  aria-current={active ? 'true' : undefined}
                  data-testid="conversation-item"
                >
                  <span className="ci-title">{item.title || '新会话'}</span>
                  <span className="ci-meta">
                    {formatWhen(item.lastMessageAt)} · {item.messageCount} 条消息
                    {scopeLabel(item.scope) ? ` · ${scopeLabel(item.scope)}` : ''}
                    {active ? ' · 当前会话' : ''}
                  </span>
                  {resuming === item.conversationId && (
                    <span className="ci-loading">正在打开…</span>
                  )}
                </button>

                {editing ? (
                  <div className="ci-rename">
                    <Input
                      size="small"
                      autoFocus
                      maxLength={TITLE_MAX_LENGTH}
                      value={draftTitle}
                      placeholder="留空恢复自动标题"
                      aria-label="会话名称"
                      onChange={(e) => setDraftTitle(e.target.value)}
                      onPressEnter={() => submitRename(item)}
                      onKeyDown={(e) => {
                        if (e.key === 'Escape') cancelRename();
                      }}
                    />
                    <Button
                      type="primary"
                      size="small"
                      loading={busy}
                      onClick={() => submitRename(item)}
                    >
                      保存
                    </Button>
                    <Button size="small" onClick={cancelRename}>
                      取消
                    </Button>
                  </div>
                ) : (
                  <div className="ci-actions">
                    <Button
                      type="text"
                      size="small"
                      icon={<EditOutlined />}
                      aria-label={`重命名「${item.title || '新会话'}」`}
                      onClick={() => startRename(item)}
                      disabled={resuming !== null || busy}
                    />
                    <Popconfirm
                      title="删除这段会话？"
                      description="聊天记录会被永久删除，无法恢复。"
                      okText="删除"
                      cancelText="取消"
                      okButtonProps={{ danger: true }}
                      onConfirm={() => onDelete(item)}
                    >
                      <Button
                        type="text"
                        size="small"
                        danger
                        icon={<DeleteOutlined />}
                        aria-label={`删除「${item.title || '新会话'}」`}
                        disabled={resuming !== null || busy}
                      />
                    </Popconfirm>
                  </div>
                )}
              </div>
            );
          })}
        </div>
      )}
    </Drawer>
  );
}
