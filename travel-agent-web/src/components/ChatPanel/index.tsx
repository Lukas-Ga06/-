import { useEffect, useRef, useState } from 'react';
import { App, Button, Input, Popconfirm, Tooltip } from 'antd';
import {
  BookOutlined,
  CloseOutlined,
  CloudOutlined,
  CompassOutlined,
  EnvironmentOutlined,
  HistoryOutlined,
  MessageOutlined,
  PlusOutlined,
  RobotOutlined,
  SendOutlined,
  StopOutlined,
  WalletOutlined,
} from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import { useAgentStore } from '../../store/agentStore';
import { useAuthStore } from '../../store/authStore';
import { useUIStore } from '../../store/uiStore';
import { retryExecution } from '../../api/agent';
import VoiceButton from '../VoiceButton';
import AgentActivityPanel from '../AgentActivityPanel';
import ExecutionResultPanel from '../ExecutionResultPanel';
import AgentRunsDrawer from '../AgentRunsDrawer';
import ConversationDrawer from '../ConversationDrawer';
import CitationList from '../CitationList';
import ResultCard from '../ResultCard';
import RunStatus from '../RunStatus';

interface Props {
  tripId?: number | null;
  placeholder?: string;
}

/**
 * 空对话只放 3–4 个明确任务入口（P1-G）：
 * 按钮文案直接表达「能完成什么」，点下去仍然走同一条 Agent 链路。
 */
const QUICK_TASKS: { label: string; prompt: string; icon: React.ReactNode }[] = [
  { label: '规划旅行', prompt: '帮我规划一次旅行', icon: <CompassOutlined aria-hidden="true" /> },
  {
    label: '根据天气调整',
    prompt: '明天下雨，帮我根据天气调整行程安排',
    icon: <CloudOutlined aria-hidden="true" />,
  },
  { label: '记一笔消费', prompt: '记一笔消费', icon: <WalletOutlined aria-hidden="true" /> },
  {
    label: '查询攻略',
    prompt: '查询旅行攻略：目的地有什么值得去的地方？',
    icon: <BookOutlined aria-hidden="true" />,
  },
];

/** AI 助手聊天面板：文字 + 语音输入，SSE 流式接收 Agent 过程与结果 */
export default function ChatPanel({ tripId, placeholder }: Props) {
  const navigate = useNavigate();
  const messages = useAgentStore((s) => s.messages);
  const streaming = useAgentStore((s) => s.streaming);
  const stage = useAgentStore((s) => s.stage);
  const activity = useAgentStore((s) => s.activity);
  const warnings = useAgentStore((s) => s.warnings);
  const lastExecution = useAgentStore((s) => s.lastExecution);
  const setLastExecution = useAgentStore((s) => s.setLastExecution);
  const send = useAgentStore((s) => s.send);
  const abort = useAgentStore((s) => s.abort);
  const cancel = useAgentStore((s) => s.cancel);
  const newConversation = useAgentStore((s) => s.newConversation);
  const user = useAuthStore((s) => s.user);
  const selectedDay = useUIStore((s) => s.selectedDay);
  const selectedElement = useUIStore((s) => s.selectedElement);
  const setSelectedElement = useUIStore((s) => s.setSelectedElement);
  const { message } = App.useApp();
  const effectivePlaceholder = placeholder ?? '输入旅行需求，或对行程提出调整…';
  const [text, setText] = useState('');
  const listRef = useRef<HTMLDivElement>(null);

  // 执行结果面板：用户点关闭后隐藏，收到新的 execution（新一次确认提交）再出现。
  // 用「值变化时在渲染期收敛」重置，避免 effect 内同步 setState 多跑一轮渲染。
  const [executionHidden, setExecutionHidden] = useState(false);
  const [executionSeed, setExecutionSeed] = useState(lastExecution?.executionId);
  if (executionSeed !== lastExecution?.executionId) {
    setExecutionSeed(lastExecution?.executionId);
    setExecutionHidden(false);
  }
  const [retrying, setRetrying] = useState(false);
  const [runsOpen, setRunsOpen] = useState(false);
  /** 最近会话抽屉（§8.6）：继续历史对话或开新对话 */
  const [conversationsOpen, setConversationsOpen] = useState(false);

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

  /** 最后一条结构化结果：只给它挂本轮过程警告，避免旧结果被新警告污染 */
  const lastResultMessage = [...messages].reverse().find((m) => m.role === 'assistant' && m.result);
  const lastResult = lastResultMessage?.result ?? null;
  const lastUserText = [...messages].reverse().find((m) => m.role === 'user')?.content ?? '';
  const retryLast = () => {
    if (lastUserText) sendText(lastUserText);
  };
  const openTrip = (id: number) => navigate(`/trip/${id}`);

  /**
   * 结果后的可点击建议（§8.6）：空态示例之外，结果出来时也要给「下一步能做什么」，
   * 否则用户只能自己想问题。按下轮最可能的动作给 2–3 个。
   */
  const followups: string[] =
    streaming || !lastResult
      ? []
      : lastResult.status === 'NEED_CONFIRMATION'
        ? ['预算再低一点', '节奏放慢一些']
        : lastResult.status === 'COMPLETED'
          ? ['记一笔消费', '帮我看看还有什么没安排']
          : [];
  const retryable =
    !streaming &&
    (lastResult?.status === 'FAILED' ||
      lastResult?.status === 'CANCELLED' ||
      (!lastResult &&
        messages.length > 0 &&
        messages[messages.length - 1].role === 'assistant' &&
        messages[messages.length - 1].content.startsWith('⚠'))) &&
    lastUserText.length > 0;
  const showFollowups = followups.length > 0 || retryable;

  return (
    <>
      <div className="chat-messages" ref={listRef}>
        {/* 过程时间线在消息区内部、首次问候之前；空数组时组件自身返回 null。
            同步台账不是 SSE 事件，由确认提交后的 ExecutionSummary 驱动，作为「同步台账」阶段追加 */}
        <AgentActivityPanel activity={activity} execution={lastExecution} />
        {messages.length === 0 && (
          <div className="chat-empty" data-testid="chat-empty">
            <div className="chat-row">
              <div className="chat-avatar ai" aria-hidden="true">
                <RobotOutlined />
              </div>
              <div className="chat-bubble assistant">
                你好，我是 AI 旅行助手。
                {'\n'}可以直接说，也可以点下面的入口开始：
              </div>
            </div>
            <div className="chat-suggest" data-testid="chat-suggestions">
              {QUICK_TASKS.map((task) => (
                <button
                  key={task.label}
                  type="button"
                  className="suggest-chip"
                  onClick={() => sendText(task.prompt)}
                  disabled={streaming}
                  data-prompt={task.prompt}
                >
                  {task.icon}
                  {task.label}
                </button>
              ))}
            </div>
          </div>
        )}
        {messages.map((m) => (
          <div key={m.id} className={`chat-row ${m.role}`}>
            <div className={`chat-avatar ${m.role === 'user' ? 'user' : 'ai'}`}>
              {m.role === 'user' ? (
                (user?.nickname || user?.username || '我')[0]
              ) : (
                <RobotOutlined aria-hidden="true" />
              )}
            </div>
            {/* 结构化结果卡只渲染一次：来源/影响范围/待核实都在卡片内，气泡不再重复正文 */}
            {m.role === 'assistant' && m.result ? (
              <ResultCard
                result={m.result}
                warnings={m.id === lastResultMessage?.id ? warnings : []}
                onRetry={retryLast}
                onCancelPlan={() => cancel()}
                onOpenTrip={openTrip}
              />
            ) : (
              <div className={`chat-bubble ${m.role}`}>
                {m.content}
                {/* assistant 方案的知识依据；空数组时组件返回 null */}
                {m.role === 'assistant' && <CitationList citations={m.citations} compact />}
              </div>
            )}
          </div>
        ))}
        {streaming && stage && (
          <div className="chat-stage">
            <RunStatus status="loading" text={stage} size="sm" />
            <span className="chat-stage-stop-hint">可随时停止，已生成的内容会保留。</span>
          </div>
        )}
        {/* 结果后的下一步建议：结果卡里已经有待核实项，这里只给「还能做什么」 */}
        {showFollowups && (
          <div className="chat-suggest" data-testid="chat-followups">
            {followups.map((p) => (
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
            {retryable && (
              <button type="button" className="suggest-chip" onClick={retryLast}>
                重试上一次
              </button>
            )}
          </div>
        )}
        {/* 过程警告：没有结构化结果卡时（例如旧后端 / 纯文本回答）仍需在结论旁显示 */}
        {!streaming && warnings.length > 0 && !lastResult && (
          <div className="chat-warnings" role="status">
            <div className="cw-head">
              <RunStatus status="degraded" text="⚠ 部分内容待核实" size="sm" showIcon={false} />
            </div>
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
          <button
            type="button"
            className="mini-tag indoor selected-chip-btn"
            onClick={() => setSelectedElement(null)}
            aria-label={`取消选中：${selectedElement.title}`}
          >
            <EnvironmentOutlined aria-hidden="true" /> 已选中：{selectedElement.title}{' '}
            <CloseOutlined aria-hidden="true" />
          </button>
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
            className="chat-tool-btn chat-tool-btn--runs"
            style={{ flexShrink: 0 }}
          />
        </Tooltip>
        <Tooltip title="最近会话：继续之前的对话，或开一段新的">
          <Button
            type="text"
            size="small"
            icon={<MessageOutlined />}
            onClick={() => setConversationsOpen(true)}
            aria-label="最近会话"
            className="chat-tool-btn"
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
            <Tooltip title="开新对话（清除会话记忆）">
              <Button
                type="text"
                size="small"
                icon={<PlusOutlined />}
                aria-label="开新对话"
                className="chat-tool-btn"
                style={{ flexShrink: 0 }}
              />
            </Tooltip>
          </Popconfirm>
        )}
        <Input.TextArea
          value={text}
          onChange={(e) => setText(e.target.value)}
          placeholder={effectivePlaceholder}
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
      <ConversationDrawer open={conversationsOpen} onClose={() => setConversationsOpen(false)} />
    </>
  );
}
