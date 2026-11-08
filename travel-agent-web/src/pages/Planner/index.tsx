import { useEffect, useRef, useState } from 'react';
import { App, Button, Checkbox, DatePicker, Input, InputNumber, Radio } from 'antd';
import { ArrowLeftOutlined, DeleteOutlined, PlusOutlined } from '@ant-design/icons';
import { useNavigate, useSearchParams } from 'react-router-dom';
import dayjs, { type Dayjs } from 'dayjs';
import ChatPanel from '../../components/ChatPanel';
import PlanDiff from '../../components/PlanDiff';
import { useAgentStore } from '../../store/agentStore';
import {
  compilePlanPrompt,
  compilePlanSummary,
  clampPlanMessage,
  emptyPlanForm,
  FOCUS_OPTIONS,
  guidesTotalChars,
  GUIDE_MAX_CHARS,
  GUIDES_MAX_TOTAL,
  INTEREST_OPTIONS,
  isFormSubmittable,
  type CompanionType,
  type Pace,
  type PlanFormState,
} from './planForm';

const COMPANION_CHIPS: { key: CompanionType; label: string }[] = [
  { key: 'alone', label: '独自' },
  { key: 'partner', label: '伴侣' },
  { key: 'friends', label: '朋友' },
  { key: 'parents', label: '带父母' },
  { key: 'kids', label: '带儿童' },
  { key: 'other', label: '其他' },
];

const PACE_CHIPS: { key: Pace; label: string; desc: string }[] = [
  { key: 'relaxed', label: '轻松悠闲', desc: '行程留白更多' },
  { key: 'balanced', label: '张弛有度', desc: '重点体验与休息兼顾' },
  { key: 'packed', label: '尽量多玩', desc: '接受更高行程密度' },
];

/** 规划页：结构化需求表单（攻略整合 / 从零规划）→ Agent 生成 → 对话局部调整 */
export default function Planner() {
  const navigate = useNavigate();
  const [params] = useSearchParams();
  const { message } = App.useApp();
  const streaming = useAgentStore((s) => s.streaming);
  const messages = useAgentStore((s) => s.messages);
  const pendingConfirm = useAgentStore((s) => s.pendingConfirm);
  const confirm = useAgentStore((s) => s.confirm);
  const cancel = useAgentStore((s) => s.cancel);
  const committedTripId = useAgentStore((s) => s.committedTripId);
  const clearCommitted = useAgentStore((s) => s.clearCommitted);
  const reset = useAgentStore((s) => s.reset);
  const newConversation = useAgentStore((s) => s.newConversation);
  const send = useAgentStore((s) => s.send);

  // 从旅行页「一句话规划」跳来时，把那句话放进特别要求，由用户补全表单后提交
  const [form, setForm] = useState<PlanFormState>(() => {
    const base = emptyPlanForm();
    const prompt = params.get('prompt');
    return prompt ? { ...base, mode: 'scratch', notes: prompt } : base;
  });
  /** 已提交进入「规划工作区」（表单让位给对话） */
  const [started, setStarted] = useState(false);
  const submittedRef = useRef(false);

  useEffect(() => {
    reset();
    return () => reset();
  }, []);

  useEffect(() => {
    if (committedTripId) {
      const id = committedTripId;
      clearCommitted();
      navigate(`/trip/${id}`);
    }
  }, [committedTripId]);

  const patch = (p: Partial<PlanFormState>) => setForm((prev) => ({ ...prev, ...p }));

  const inWorkspace = started || messages.length > 0 || streaming;

  const submit = () => {
    if (streaming || submittedRef.current) return;
    if (!form.destination.trim()) {
      message.warning('先填写目的地');
      return;
    }
    if (!isFormSubmittable(form)) {
      message.warning(form.mode === 'guides' ? '至少粘贴一篇攻略正文' : '请填写出行时间');
      return;
    }
    submittedRef.current = true;
    send({
      message: clampPlanMessage(compilePlanPrompt(form)),
      displayText: compilePlanSummary(form),
      transport: 'post',
    });
    setStarted(true);
  };

  const backToForm = () => {
    submittedRef.current = false;
    setStarted(false);
    // 返回修改需求意味着重新提交会创建新方案：旧会话历史一并清掉
    newConversation();
  };

  const guidesTotal = guidesTotalChars(form.guides);
  const timeMode = form.startDate && form.endDate ? 'dates' : 'days';

  return (
    <div className="planner-page">
      <div className="trip-header">
        <Button type="text" icon={<ArrowLeftOutlined />} onClick={() => navigate('/')} />
        <div className="th-title">
          <b>规划新行程</b>
          <span className="th-meta">填写需求或粘贴攻略，Agent 会自动完成合并、搜索、排期与预算</span>
        </div>
      </div>
      <div className="planner-body">
        <div className="planner-card fade-up">
          {inWorkspace ? (
            <>
              <div className="chat-head">
                <div className="ch-avatar">🤖</div>
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
                  <Button size="small" onClick={backToForm} disabled={streaming}>
                    返回修改需求
                  </Button>
                </div>
              </div>
              <ChatPanel placeholder="继续调整，如：第二天太赶了，删掉一个景点" />
            </>
          ) : (
            <div className="plan-form-scroll">
              {/* ---------- 规划方式 ---------- */}
              <div className="plan-sec">
                <div className="plan-sec-head">
                  <b>选择规划方式</b>
                  <span>两种方式都会结合下方填写的个人旅行约束</span>
                </div>
                <div className="plan-mode-grid">
                  <button
                    type="button"
                    className={`plan-mode-btn ${form.mode === 'guides' ? 'active' : ''}`}
                    onClick={() => patch({ mode: 'guides' })}
                  >
                    <b>
                      我已经收藏了攻略 <span className="mini-tag indoor">核心体验</span>
                    </b>
                    <span>粘贴最多三篇小红书攻略、朋友行程或自己的草稿，我会合并、筛选和重新安排。</span>
                  </button>
                  <button
                    type="button"
                    className={`plan-mode-btn ${form.mode === 'scratch' ? 'active' : ''}`}
                    onClick={() => patch({ mode: 'scratch' })}
                  >
                    <b>我还没有攻略</b>
                    <span>告诉我目的地、时间和偏好，帮我从零建立旅行计划。</span>
                  </button>
                </div>
              </div>

              {/* ---------- 参考攻略（攻略模式） ---------- */}
              {form.mode === 'guides' && (
                <div className="plan-sec">
                  <div className="plan-sec-head">
                    <b>添加你收藏的攻略</b>
                    <span>可以是完整行程，也可以只是美食、景点、拍照点或当地活动</span>
                  </div>
                  {form.guides.map((g, i) => (
                    <div className="guide-card" key={i}>
                      <div className="guide-card-head">
                        <span>参考攻略 {i + 1}</span>
                        {form.guides.length > 1 && (
                          <Button
                            size="small"
                            type="text"
                            danger
                            icon={<DeleteOutlined />}
                            onClick={() => patch({ guides: form.guides.filter((_, j) => j !== i) })}
                          >
                            删除本篇
                          </Button>
                        )}
                      </div>
                      <Input
                        placeholder="标题或来源（选填），如：小红书｜伦敦五日景点攻略"
                        value={g.title}
                        maxLength={120}
                        onChange={(e) =>
                          patch({
                            guides: form.guides.map((x, j) => (j === i ? { ...x, title: e.target.value } : x)),
                          })
                        }
                        style={{ marginBottom: 8 }}
                      />
                      <Input.TextArea
                        rows={5}
                        placeholder="粘贴攻略正文……"
                        value={g.content}
                        maxLength={GUIDE_MAX_CHARS}
                        showCount
                        onChange={(e) =>
                          patch({
                            guides: form.guides.map((x, j) => (j === i ? { ...x, content: e.target.value } : x)),
                          })
                        }
                      />
                    </div>
                  ))}
                  <div className="plan-row" style={{ justifyContent: 'space-between' }}>
                    <Button
                      icon={<PlusOutlined />}
                      disabled={form.guides.length >= 3}
                      onClick={() => patch({ guides: [...form.guides, { title: '', content: '' }] })}
                    >
                      再添加一篇攻略
                    </Button>
                    <span className="plan-counter">
                      三篇正文合计 {guidesTotal}/{GUIDES_MAX_TOTAL} 字
                    </span>
                  </div>
                  <div className="plan-sec-head" style={{ marginTop: 14 }}>
                    <b>你希望重点优化什么？</b>
                    <span>可多选，也可以暂不选择</span>
                  </div>
                  <div className="plan-chip-row">
                    {FOCUS_OPTIONS.map((f) => (
                      <button
                        type="button"
                        key={f}
                        className={`suggest-chip ${form.focus.includes(f) ? 'active' : ''}`}
                        onClick={() =>
                          patch({
                            focus: form.focus.includes(f)
                              ? form.focus.filter((x) => x !== f)
                              : [...form.focus, f],
                          })
                        }
                      >
                        {f}
                      </button>
                    ))}
                  </div>
                  <div className="plan-sec-head" style={{ marginTop: 14 }}>
                    <b>哪些内容不能被修改？</b>
                    <span>例如已经购买的门票、必须去的景点、已预约的活动，或已确定的住宿位置</span>
                  </div>
                  <Input.TextArea
                    rows={2}
                    placeholder="例如：第三天下午已购买大英博物馆特展门票，请勿移动。"
                    value={form.locked}
                    maxLength={300}
                    onChange={(e) => patch({ locked: e.target.value })}
                  />
                </div>
              )}

              {/* ---------- 目的地与时间 ---------- */}
              <div className="plan-sec">
                <div className="plan-sec-head">
                  <b>目的地与时间</b>
                  <span>这两项用于建立旅行计划的基础范围</span>
                </div>
                <div className="plan-row">
                  <Input
                    placeholder="目的地，例如：东京、巴黎、伦敦"
                    value={form.destination}
                    maxLength={60}
                    style={{ flex: '1 1 200px' }}
                    onChange={(e) => patch({ destination: e.target.value })}
                  />
                  <Radio.Group
                    value={timeMode}
                    onChange={(e) =>
                      patch(
                        e.target.value === 'dates'
                          ? { days: null, startDate: form.startDate ?? dayjs().format('YYYY-MM-DD'), endDate: form.endDate ?? dayjs().add(2, 'day').format('YYYY-MM-DD') }
                          : { startDate: null, endDate: null, days: form.days ?? 3 },
                      )
                    }
                    optionType="button"
                    buttonStyle="solid"
                    options={[
                      { value: 'days', label: '旅行天数' },
                      { value: 'dates', label: '具体日期' },
                    ]}
                  />
                  {timeMode === 'days' ? (
                    <InputNumber
                      min={1}
                      max={30}
                      value={form.days ?? undefined}
                      placeholder="几天？"
                      addonAfter="天"
                      style={{ width: 130 }}
                      onChange={(v) => patch({ days: v ?? null })}
                    />
                  ) : (
                    <DatePicker.RangePicker
                      value={
                        form.startDate && form.endDate
                          ? [dayjs(form.startDate), dayjs(form.endDate)] as [Dayjs, Dayjs]
                          : null
                      }
                      onChange={(range) =>
                        patch({
                          startDate: range?.[0]?.format('YYYY-MM-DD') ?? null,
                          endDate: range?.[1]?.format('YYYY-MM-DD') ?? null,
                        })
                      }
                    />
                  )}
                </div>
              </div>

              {/* ---------- 同行人员 ---------- */}
              <div className="plan-sec">
                <div className="plan-sec-head">
                  <b>同行人员</b>
                  <span>用于判断节奏、房型和活动适配度</span>
                </div>
                <div className="plan-chip-row">
                  {COMPANION_CHIPS.map((c) => (
                    <button
                      type="button"
                      key={c.key}
                      className={`suggest-chip ${form.companion === c.key ? 'active' : ''}`}
                      onClick={() => patch({ companion: c.key })}
                    >
                      {c.label}
                    </button>
                  ))}
                </div>
                <div className="plan-row">
                  <span className="plan-counter">成人</span>
                  <InputNumber
                    min={1}
                    max={20}
                    value={form.adults}
                    style={{ width: 90 }}
                    onChange={(v) => patch({ adults: v ?? 1 })}
                  />
                  <span className="plan-counter">儿童</span>
                  <InputNumber
                    min={0}
                    max={20}
                    value={form.kids}
                    style={{ width: 90 }}
                    onChange={(v) => patch({ kids: v ?? 0 })}
                  />
                </div>
              </div>

              {/* ---------- 预算与偏好 ---------- */}
              <div className="plan-sec">
                <div className="plan-sec-head">
                  <b>预算与偏好</b>
                  <span>可选信息，帮助方案更贴近真实决策</span>
                </div>
                <div className="plan-row">
                  <Checkbox
                    checked={form.budgetEnabled}
                    onChange={(e) => patch({ budgetEnabled: e.target.checked })}
                  >
                    设置旅行预算
                  </Checkbox>
                  {form.budgetEnabled && (
                    <InputNumber
                      min={100}
                      max={1000000}
                      step={100}
                      value={form.budget ?? undefined}
                      placeholder="总预算"
                      addonBefore="¥"
                      style={{ width: 160 }}
                      onChange={(v) => patch({ budget: v ?? null })}
                    />
                  )}
                </div>
                <div className="plan-chip-row">
                  {PACE_CHIPS.map((p) => (
                    <button
                      type="button"
                      key={p.key}
                      className={`plan-mode-btn ${form.pace === p.key ? 'active' : ''}`}
                      style={{ padding: '8px 12px' }}
                      onClick={() => patch({ pace: p.key })}
                    >
                      <b style={{ fontSize: 13 }}>{p.label}</b>
                      <span style={{ marginTop: 1 }}>{p.desc}</span>
                    </button>
                  ))}
                </div>
                <div className="plan-sec-head" style={{ marginTop: 14 }}>
                  <b>想体验什么？</b>
                </div>
                <div className="plan-chip-row">
                  {INTEREST_OPTIONS.map((it) => (
                    <button
                      type="button"
                      key={it}
                      className={`suggest-chip ${form.interests.includes(it) ? 'active' : ''}`}
                      onClick={() =>
                        patch({
                          interests: form.interests.includes(it)
                            ? form.interests.filter((x) => x !== it)
                            : [...form.interests, it],
                        })
                      }
                    >
                      {it}
                    </button>
                  ))}
                </div>
                <div className="plan-sec-head" style={{ marginTop: 14 }}>
                  <b>特别要求</b>
                </div>
                <Input.TextArea
                  rows={2}
                  placeholder="例如：和父母同行，希望少走路；不吃辣；酒店靠近地铁。"
                  value={form.notes}
                  maxLength={500}
                  onChange={(e) => patch({ notes: e.target.value })}
                />
              </div>

              <div className="plan-footer">
                <Button
                  onClick={() => {
                    setForm(emptyPlanForm());
                  }}
                >
                  清空全部需求
                </Button>
                <Button type="primary" size="large" onClick={submit} loading={streaming}>
                  {form.mode === 'guides' ? '整合攻略并生成计划' : '根据我的需求生成计划'}
                </Button>
              </div>
            </div>
          )}
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
