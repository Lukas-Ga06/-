import { useEffect, useRef } from 'react';
import { Button } from 'antd';
import { ArrowLeftOutlined } from '@ant-design/icons';
import { useNavigate, useSearchParams } from 'react-router-dom';
import ChatPanel from '../../components/ChatPanel';
import PlanDiff from '../../components/PlanDiff';
import { useAgentStore } from '../../store/agentStore';

/** 规划页：从首页进入后自动发送旅行需求，Agent 规划确认后跳转到行程页 */
export default function Planner() {
  const navigate = useNavigate();
  const [params] = useSearchParams();
  const send = useAgentStore((s) => s.send);
  const streaming = useAgentStore((s) => s.streaming);
  const pendingConfirm = useAgentStore((s) => s.pendingConfirm);
  const confirm = useAgentStore((s) => s.confirm);
  const cancel = useAgentStore((s) => s.cancel);
  const committedTripId = useAgentStore((s) => s.committedTripId);
  const clearCommitted = useAgentStore((s) => s.clearCommitted);
  const reset = useAgentStore((s) => s.reset);
  const startedRef = useRef(false);

  useEffect(() => {
    reset();
    const prompt = params.get('prompt');
    if (prompt && !startedRef.current) {
      startedRef.current = true;
      setTimeout(() => send({ message: prompt }), 150);
    }
    return () => reset();
  }, []);

  useEffect(() => {
    if (committedTripId) {
      const id = committedTripId;
      clearCommitted();
      navigate(`/trip/${id}`);
    }
  }, [committedTripId]);

  return (
    <div className="planner-page">
      <div className="trip-header">
        <Button type="text" icon={<ArrowLeftOutlined />} onClick={() => navigate('/')} />
        <div className="th-title">
          <b>规划新行程</b>
          <span className="th-meta">描述你的旅行需求，Agent 会自动完成搜索、排期与预算</span>
        </div>
      </div>
      <div className="planner-body">
        <div className="planner-card fade-up">
          <div className="chat-head">
            <div className="ch-avatar">🤖</div>
            <div>
              <b>AI 旅行规划师</b>
              <span>输入旅行需求，实时展示规划过程</span>
            </div>
          </div>
          <ChatPanel placeholder="描述你的旅行计划，如：上海三日游，预算3000元…" />
        </div>
      </div>
      <PlanDiff
        open={!!pendingConfirm}
        result={pendingConfirm}
        onConfirm={() => {
          confirm();
        }}
        onCancel={() => cancel()}
        loading={streaming}
      />
    </div>
  );
}
