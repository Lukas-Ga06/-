import { useEffect, useRef } from 'react';
import { useSearchParams } from 'react-router-dom';
import AppShell from '../../components/AppShell';
import ChatPanel from '../../components/ChatPanel';
import PlanDiff from '../../components/PlanDiff';
import { getPendingPlan } from '../../api/agent';
import { useAgentStore } from '../../store/agentStore';

/** AI 助理：统一对话入口（旅行规划 / 记账 / 备忘 / 日报 / 知识问答） */
export default function Assistant() {
  const [params, setParams] = useSearchParams();
  const send = useAgentStore((s) => s.send);
  const reset = useAgentStore((s) => s.reset);
  const pendingConfirm = useAgentStore((s) => s.pendingConfirm);
  const confirm = useAgentStore((s) => s.confirm);
  const cancel = useAgentStore((s) => s.cancel);
  const streaming = useAgentStore((s) => s.streaming);
  const restorePending = useAgentStore((s) => s.restorePending);
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

  // 刷新后凭 URL 上的 planId 找回待确认方案（此前刷新即丢，只能重新发起）
  useEffect(() => {
    const planId = params.get('planId');
    if (!planId || useAgentStore.getState().pendingConfirm) return;
    let alive = true;
    getPendingPlan(planId)
      .then((result) => {
        if (alive) restorePending(result);
      })
      .catch(() => {
        // 方案已过期/不属于当前用户：静默忽略（后端返回 404）
      });
    return () => {
      alive = false;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [params]);

  // 有待确认方案时把 planId 写进 URL，方便刷新与分享定位
  useEffect(() => {
    const planId = pendingConfirm?.planId;
    setParams(
      (prev) => {
        const next = new URLSearchParams(prev);
        if (planId) {
          next.set('planId', planId);
        } else {
          next.delete('planId');
        }
        return next;
      },
      { replace: true },
    );
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [pendingConfirm?.planId]);

  // acknowledgeIssues：存在未解决高风险时由确认弹窗的勾选门槛传入
  const onConfirm = async (acknowledgeIssues?: boolean) => {
    await confirm(acknowledgeIssues);
  };

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
        <PlanDiff
          open={!!pendingConfirm}
          result={pendingConfirm}
          onConfirm={onConfirm}
          onCancel={() => cancel()}
          loading={streaming}
        />
      </div>
    </AppShell>
  );
}
