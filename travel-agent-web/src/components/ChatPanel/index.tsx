import { useEffect, useRef, useState } from 'react';
import { App, Button, Input, Popconfirm, Tooltip } from 'antd';
import { CloseOutlined, HistoryOutlined, PlusOutlined, SendOutlined } from '@ant-design/icons';
import { useAgentStore } from '../../store/agentStore';
import { useAuthStore } from '../../store/authStore';
import { useUIStore } from '../../store/uiStore';
import { retryExecution } from '../../api/agent';
import VoiceButton from '../VoiceButton';
import AgentActivityPanel from '../AgentActivityPanel';
import ExecutionResultPanel from '../ExecutionResultPanel';
import AgentRunsDrawer from '../AgentRunsDrawer';

interface Props {
  tripId?: number | null;
  placeholder?: string;
}

/** AI 助手聊天面板：文字 + 语音输入，SSE 流式接收 Agent 过程与结果 */
export default function ChatPanel({ tripId, placeholder }: Props) {
  const messages = useAgentStore((s) => s.messages);
  const streaming = useAgentStore((s) => s.streaming);
  const stage = useAgentStore((s) => s.stage);
  const activity = useAgentStore((s) => s.activity);
  const lastExecution = useAgentStore((s) => s.lastExecution);
  const setLastExecution = useAgentStore((s) => s.setLastExecution);
  const send = useAgentStore((s) => s.send);
  const newConversation = useAgentStore((s) => s.newConversation);
  const user = useAuthStore((s) => s.user);
  const selectedDay = useUIStore((s) => s.selectedDay);
  const selectedElement = useUIStore((s) => s.selectedElement);
  const setSelectedElement = useUIStore((s) => s.setSelectedElement);
  const { message } = App.useApp();
  const [text, setText] = useState('');
  const listRef = useRef<HTMLDivElement>(null);

  // 执行结果面板：用户点关闭后隐藏，收到新的 execution（新一次确认提交）再出现
  const [executionHidden, setExecutionHidden] = useState(false);
  const [retrying, setRetrying] = useState(false);
  const [runsOpen, setRunsOpen] = useState(false);

  useEffect(() => {
    setExecutionHidden(false);
  }, [lastExecution?.executionId]);

  useEffect(() => {
    listRef.current?.scrollTo({ top: listRef.current.scrollHeight, behavior: 'smooth' });
  }, [messages, stage]);

  const doSend = () => {
    const value = text.trim();
    if (!value || streaming) return;
    setText('');
    send({
      message: value,
      tripId: tripId ?? null,
      currentDay: selectedDay,
      selectedElement,
    });
  };

  // 模块级同步失败重试：后端只重试 FAILED 模块，成功项不重复创建
  const onRetryExecution = async () => {
    if (!lastExecution || retrying) return;
    setRetrying(true);
    try {
      const next = await retryExecution(lastExecution.executionId);
      setLastExecution(next);
    } catch (e: any) {
      message.error(e?.message || '重试失败，请稍后再试');
    } finally {
      setRetrying(false);
    }
  };

  return (
    <>
      <div className="chat-messages" ref={listRef}>
        {/* 过程时间线在消息区内部、首次问候之前；空数组时组件自身返回 null */}
        <AgentActivityPanel activity={activity} />
        {messages.length === 0 && (
          <div className="chat-row">
            <div className="chat-avatar ai">🤖</div>
            <div className="chat-bubble assistant">
              你好，我是 AI 旅行助手 ✈️
              {'\n'}可以说：
              {'\n'}· 帮我规划上海三日游，预算3000，酒店近地铁
              {'\n'}· 明天下雨，把户外景点换掉
              {'\n'}· 点选时间轴安排后说「这个换成类似的」
              {'\n'}· 上海有什么适合亲子游的地方？
            </div>
          </div>
        )}
        {messages.map((m) => (
          <div key={m.id} className={`chat-row ${m.role}`}>
            <div className={`chat-avatar ${m.role === 'user' ? 'user' : 'ai'}`}>
              {m.role === 'user' ? (user?.nickname || user?.username || '我')[0] : '🤖'}
            </div>
            <div className={`chat-bubble ${m.role}`}>{m.content}</div>
          </div>
        ))}
        {streaming && stage && (
          <div className="chat-stage">
            <span className="stage-dots">
              <i />
              <i />
              <i />
            </span>
            {stage}
          </div>
        )}
      </div>

      {lastExecution && !executionHidden && (
        <ExecutionResultPanel
          execution={lastExecution}
          onRetry={onRetryExecution}
          retrying={retrying}
          onClose={() => setExecutionHidden(true)}
        />
      )}

      {selectedElement && (
        <div className="selected-chip">
          <span
            className="mini-tag indoor"
            style={{ cursor: 'pointer', fontSize: 12, padding: '4px 12px' }}
            onClick={() => setSelectedElement(null)}
          >
            📍 已选中：{selectedElement.title} ✕
          </span>
        </div>
      )}

      <div className="chat-input-area">
        <Tooltip title="查看最近的 Agent 运行记录">
          <Button
            type="text"
            size="small"
            icon={<HistoryOutlined />}
            onClick={() => setRunsOpen(true)}
            aria-label="运行记录"
            style={{ flexShrink: 0 }}
          />
        </Tooltip>
        {messages.length > 0 && !streaming && (
          <Popconfirm
            title="开新对话？"
            description="会清空当前聊天记录和上下文记忆"
            okText="开新对话"
            cancelText="取消"
            onConfirm={newConversation}
          >
            <Button
              type="text"
              size="small"
              icon={<PlusOutlined />}
              title="开新对话（清除会话记忆）"
              style={{ flexShrink: 0 }}
            />
          </Popconfirm>
        )}
        <Input.TextArea
          value={text}
          onChange={(e) => setText(e.target.value)}
          placeholder={placeholder ?? '输入旅行需求，或对行程提出调整…'}
          autoSize={{ minRows: 1, maxRows: 4 }}
          onPressEnter={(e) => {
            if (!e.shiftKey) {
              e.preventDefault();
              doSend();
            }
          }}
        />
        <VoiceButton onText={(t) => setText(t)} />
        <Button
          type="primary"
          icon={streaming ? <CloseOutlined /> : <SendOutlined />}
          onClick={doSend}
          disabled={streaming || !text.trim()}
        />
      </div>

      <AgentRunsDrawer open={runsOpen} onClose={() => setRunsOpen(false)} />
    </>
  );
}
