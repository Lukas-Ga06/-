import { useEffect, useRef } from 'react';
import { useSearchParams } from 'react-router-dom';
import AppShell from '../../components/AppShell';
import ChatPanel from '../../components/ChatPanel';
import { useAgentStore } from '../../store/agentStore';

/** AI 助理：统一对话入口（旅行规划 / 记账 / 备忘 / 日报 / 知识问答） */
export default function Assistant() {
  const [params] = useSearchParams();
  const send = useAgentStore((s) => s.send);
  const reset = useAgentStore((s) => s.reset);
  const startedRef = useRef(false);

  // 从总览输入框跳转而来时自动发送一次
  useEffect(() => {
    const q = params.get('q');
    if (q && !startedRef.current) {
      startedRef.current = true;
      const tripId = Number(params.get('tripId'));
      setTimeout(
        () => send({ message: q, tripId: Number.isFinite(tripId) && tripId > 0 ? tripId : null }),
        120,
      );
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [params]);

  useEffect(() => {
    return () => {
      // 离开页面时清空会话，避免污染其他页面的聊天面板
      if (!params.get('q')) reset();
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  return (
    <AppShell active="assistant">
      <div className="dash-wrap">
        <div className="dash-greeting">
          <div>
            <h1>🤖 AI 助理</h1>
            <div className="dg-date">统一对话入口：旅行规划 · 记账 · 备忘 · 日程 · 日报 · 知识问答（意图自动路由）</div>
          </div>
        </div>
        <div
          className="dash-card fade-up"
          style={{ display: 'flex', flexDirection: 'column', minHeight: 'calc(100vh - 230px)', padding: 0 }}
        >
          <ChatPanel placeholder="告诉我你想完成什么……（如：记一下明天办签证）" />
        </div>
      </div>
    </AppShell>
  );
}
