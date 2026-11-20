import { useEffect, useRef } from 'react';
import { useSearchParams } from 'react-router-dom';
import AppShell from '../../components/AppShell';
import ChatPanel from '../../components/ChatPanel';
import PlanDiff from '../../components/PlanDiff';
import { getPendingPlan } from '../../api/agent';
import { useAgentStore } from '../../store/agentStore';

/** AI 助手：旅行场景统一对话入口（行程规划 / 记账 / 备忘 / 日程 / 旅行知识库） */
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
  const autoSendRef = useRef(false);
  const timerRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  // 离页即取消待发送的定时器：组件卸载后不能再发出请求（P0-01）
  useEffect(() => {
    return () => {
      if (timerRef.current !== null) {
        clearTimeout(timerRef.current);
        timerRef.current = null;
      }
    };
  }, []);

  useEffect(() => {
    return () => {
      // 离开页面时清空会话，避免污染其他页面的聊天面板；
      // 本页自己发起的对话（q 参数或已有消息）保留，跨页继续同一会话。
      if (!autoSendRef.current) reset();
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

  // 从总览输入框跳转而来时自动发送一次。
  // 关键：先把 q 从 URL 上消费掉，再发送 —— 否则刷新 / 前进后退会再执行一次，
  // 「记一下明天办签证」这类写入类命令就会重复落库（P0-01）。
  // 放在最后一个参数相关 effect：URL 写入以本 effect 为准，不会被 planId 同步覆盖。
  useEffect(() => {
    const q = params.get('q');
    if (!q || startedRef.current) return;
    startedRef.current = true;
    autoSendRef.current = true;
    const rawTripId = Number(params.get('tripId'));
    const tripId = Number.isFinite(rawTripId) && rawTripId > 0 ? rawTripId : null;
    setParams(
      (prev) => {
        const next = new URLSearchParams(prev);
        next.delete('q');
        next.delete('tripId');
        return next;
      },
      { replace: true },
    );
    timerRef.current = setTimeout(() => {
      timerRef.current = null;
      send({ message: q, tripId });
    }, 120);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [params]);

  // acknowledgeIssues：存在未解决高风险时由确认弹窗的勾选门槛传入
  const onConfirm = async (acknowledgeIssues?: boolean) => {
    await confirm(acknowledgeIssues);
  };

  return (
    <AppShell active="assistant">
      <div className="dash-wrap">
        <div className="dash-greeting">
          <div>
            <h1>🤖 AI 助手</h1>
            <div className="dg-date">
              旅行场景统一对话入口：行程规划 · 记账 · 备忘 · 日程 · 旅行知识库（意图自动路由）
            </div>
          </div>
        </div>
        <div
          className="dash-card fade-up"
          style={{
            display: 'flex',
            flexDirection: 'column',
            minHeight: 'calc(100vh - 230px)',
            padding: 0,
          }}
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
