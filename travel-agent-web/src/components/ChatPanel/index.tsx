import { useEffect, useRef, useState } from 'react';
import { App, Button, Input, Popconfirm, Tooltip } from 'antd';
import { HistoryOutlined, PlusOutlined, SendOutlined, StopOutlined } from '@ant-design/icons';
import { useAgentStore } from '../../store/agentStore';
import { useAuthStore } from '../../store/authStore';
import { useUIStore } from '../../store/uiStore';
import { retryExecution } from '../../api/agent';
import VoiceButton from '../VoiceButton';
import AgentActivityPanel from '../AgentActivityPanel';
import ExecutionResultPanel from '../ExecutionResultPanel';
import AgentRunsDrawer from '../AgentRunsDrawer';
import CitationList from '../CitationList';

interface Props {
  tripId?: number | null;
  placeholder?: string;
}

/** 空对话的可点击示例：覆盖规划 / 调整 / 记录 / 问答四类意图 */
const QUICK_PROMPTS = [
  '帮我规划上海三日游，2人，预算3000，酒店近地铁',
  '明天下雨，把户外景点换掉',
  '记一下明天办签证',
  '上海有什么适合亲子游的地方？',
];

/** AI 助手聊天面板：文字 + 语音输入，SSE 流式接收 Agent 过程与结果 */
export default function ChatPanel({ tripId, placeholder }: Props) {
  const messages = useAgentStore((s) => s.messages);
  const streaming = useAgentStore((s) => s.streaming);
  const stage = useAgentStore((s) => s.stage);
  const activity = useAgentStore((s) => s.activity);
  const warnings = useAgentStore((s) => s.warnings);
  const lastExecution = useAgentStore((s) => s.lastExecution);
  const setLastExecution = useAgentStore((s) => s.setLastExecution);
  const send = useAgentStore((s) => s.send);
  const abort = useAgentStore((s) => s.abort);
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

  /** 空态示例直接发送：与手输走同一条链路，保留当前行程/日期上下文 */
  const sendText = (value: string) => {
    if (streaming) return;
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
              {'\n'}可以直接说，也可以点下面的例子：
            </div>
          </div>
        )}
        {/* 空对话给可点击示例（方案 §8.6）：纯文字示例用户只会读，不会用 */}
        {messages.length === 0 && (
          <div className="chat-suggest" data-testid="chat-suggestions">
            {QUICK_PROMPTS.map((p) => (
              <button
                key={p}
                type="button"
                className="suggest-chip"
                onClick={() => sendText(p)}
                disabled={streaming}
              >
                {p}
              </button>
            ))}
          </div>
        )}
        {messages.map((m) => (
          <div key={m.id} className={`chat-row ${m.role}`}>
            <div className={`chat-avatar ${m.role === 'user' ? 'user' : 'ai'}`}>
              {m.role === 'user' ? (user?.nickname || user?.username || '我')[0] : '🤖'}
            </div>
            <div className={`chat-bubble ${m.role}`}>
              {m.content}
              {/* assistant 方案的知识依据；空数组时组件返回 null */}
              {m.role === 'assistant' && <CitationList citations={m.citations} compact />}
            </div>
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
        {/* 过程警告：结论旁边必须能看见「哪些模块没过」，不能只显示成功（P0-07） */}
        {!streaming && warnings.length > 0 && (
          <div className="chat-warnings" role="status">
            <div className="cw-head">⚠ 部分内容待核实</div>
            <ul>
              {warnings.map((w) => (
                <li key={w}>{w}</li>
              ))}
            </ul>
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
        {streaming ? (
          // 停止生成是真按钮：只要在生成中就可点，点击后立即恢复输入（P1-06）
          <Button
            danger
            type="primary"
            icon={<StopOutlined />}
            onClick={abort}
            aria-label="停止生成"
            title="停止生成"
          >
            停止
          </Button>
        ) : (
          <Button
            type="primary"
            icon={<SendOutlined />}
            onClick={doSend}
            disabled={!text.trim()}
            aria-label="发送"
          />
        )}
      </div>

      <AgentRunsDrawer open={runsOpen} onClose={() => setRunsOpen(false)} />
    </>
  );
}
