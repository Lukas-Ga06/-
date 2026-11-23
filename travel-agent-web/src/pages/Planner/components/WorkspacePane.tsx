import { Button } from 'antd';
import { RobotOutlined } from '@ant-design/icons';
import ChatPanel from '../../../components/ChatPanel';
import { compilePlanSummary, type PlanFormState } from '../planForm';

interface Props {
  form: PlanFormState;
  streaming: boolean;
  /** 返回修改需求：会清空当前会话，重新提交即生成新方案 */
  onBackToForm: () => void;
}

/**
 * 规划工作区：需求提交后表单让位给对话，顶部保留一行「当前需求」摘要，
 * 让用户随时知道自己提交了什么，也能退回去改需求。
 */
export default function WorkspacePane({ form, streaming, onBackToForm }: Props) {
  return (
    <div className="planner-card fade-up">
      <div className="chat-head">
        <div className="ch-avatar" aria-hidden="true">
          <RobotOutlined />
        </div>
        <div>
          <b>AI 旅行规划师</b>
          <span>正在按你提交的需求生成方案，可继续对话局部调整</span>
        </div>
      </div>
      <div className="plan-summary-wrap">
        <div className="plan-summary">
          <span className="plan-summary-label">当前需求</span>
          <span className="chip">{compilePlanSummary(form).replace('结构化需求提交：', '')}</span>
          <span className="plan-summary-spacer" />
          <Button size="small" onClick={onBackToForm} disabled={streaming}>
            返回修改需求
          </Button>
        </div>
      </div>
      <ChatPanel placeholder="继续调整，如：第二天太赶了，删掉一个景点" />
    </div>
  );
}
