import { Button, Checkbox, Input, InputNumber } from 'antd';
import { DeleteOutlined, PlusOutlined } from '@ant-design/icons';
import {
  FOCUS_OPTIONS,
  GUIDE_MAX_CHARS,
  GUIDES_MAX_TOTAL,
  guidesTotalChars,
  INTEREST_OPTIONS,
  type Pace,
  type PlanFormState,
} from '../planForm';
import type { PatchPlanForm } from './types';

const PACE_CHIPS: { key: Pace; label: string; desc: string }[] = [
  { key: 'relaxed', label: '轻松悠闲', desc: '行程留白更多' },
  { key: 'balanced', label: '张弛有度', desc: '重点体验与休息兼顾' },
  { key: 'packed', label: '尽量多玩', desc: '接受更高行程密度' },
];

interface Props {
  form: PlanFormState;
  patch: PatchPlanForm;
}

/**
 * 第 2 步：偏好与约束（全部可跳过）。
 * 规划方式决定是否出现攻略编辑区；预算、节奏、兴趣、锁定项都是可选信息。
 */
export default function PlanStepPreferences({ form, patch }: Props) {
  const guidesTotal = guidesTotalChars(form.guides);

  return (
    <div className="plan3-step-body">
      <div className="plan3-sec">
        <div className="plan3-sec-head">
          <b>规划方式</b>
          <span>两种方式都会结合下面的偏好与约束</span>
        </div>
        <div className="plan3-mode-grid">
          <button
            type="button"
            aria-pressed={form.mode === 'guides'}
            className={`plan3-mode ${form.mode === 'guides' ? 'on' : ''}`}
            onClick={() => patch({ mode: 'guides' })}
          >
            <b>我已经收藏了攻略</b>
            <span>粘贴最多三篇小红书攻略、朋友行程或自己的草稿，我会合并、筛选和重新安排。</span>
          </button>
          <button
            type="button"
            aria-pressed={form.mode === 'scratch'}
            className={`plan3-mode ${form.mode === 'scratch' ? 'on' : ''}`}
            onClick={() => patch({ mode: 'scratch' })}
          >
            <b>我还没有攻略</b>
            <span>告诉我目的地、时间和偏好，帮我从零建立旅行计划。</span>
          </button>
        </div>
      </div>

      {form.mode === 'guides' && (
        <div className="plan3-sec">
          <div className="plan3-sec-head">
            <b>添加你收藏的攻略</b>
            <span>可以是完整行程，也可以只是美食、景点、拍照点或当地活动</span>
          </div>
          <div className="plan3-guides">
            {form.guides.map((g, i) => (
              <div className="plan3-guide" key={i}>
                <div className="plan3-guide-head">
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
                <div className="plan3-guide-title">
                  <Input
                    placeholder="标题或来源（选填），如：小红书｜伦敦五日景点攻略"
                    value={g.title}
                    maxLength={120}
                    onChange={(e) =>
                      patch({
                        guides: form.guides.map((x, j) =>
                          j === i ? { ...x, title: e.target.value } : x,
                        ),
                      })
                    }
                  />
                </div>
                <Input.TextArea
                  rows={5}
                  placeholder="粘贴攻略正文……"
                  value={g.content}
                  maxLength={GUIDE_MAX_CHARS}
                  showCount
                  onChange={(e) =>
                    patch({
                      guides: form.guides.map((x, j) =>
                        j === i ? { ...x, content: e.target.value } : x,
                      ),
                    })
                  }
                />
              </div>
            ))}
          </div>
          <div className="plan3-row plan3-row-between">
            <Button
              icon={<PlusOutlined />}
              disabled={form.guides.length >= 3}
              onClick={() => patch({ guides: [...form.guides, { title: '', content: '' }] })}
            >
              再添加一篇攻略
            </Button>
            <span className="plan3-counter">
              三篇正文合计 {guidesTotal}/{GUIDES_MAX_TOTAL} 字
            </span>
          </div>
          <div className="plan3-sec-head plan3-sec-sub">
            <b>你希望重点优化什么？</b>
            <span>可多选，也可以暂不选择</span>
          </div>
          <div className="plan3-chips">
            {FOCUS_OPTIONS.map((f) => (
              <button
                type="button"
                key={f}
                aria-pressed={form.focus.includes(f)}
                className={`plan3-chip-btn ${form.focus.includes(f) ? 'on' : ''}`}
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
        </div>
      )}

      <div className="plan3-sec">
        <div className="plan3-sec-head">
          <b>预算与节奏</b>
          <span>可选信息，帮助方案更贴近真实决策</span>
        </div>
        <div className="plan3-row">
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
              className="plan3-num"
              onChange={(v) => patch({ budget: v ?? null })}
            />
          )}
        </div>
        <div className="plan3-pace-grid">
          {PACE_CHIPS.map((p) => (
            <button
              type="button"
              key={p.key}
              aria-pressed={form.pace === p.key}
              className={`plan3-mode ${form.pace === p.key ? 'on' : ''}`}
              onClick={() => patch({ pace: p.key })}
            >
              <b>{p.label}</b>
              <span>{p.desc}</span>
            </button>
          ))}
        </div>
        <div className="plan3-sec-head plan3-sec-sub">
          <b>想体验什么？</b>
          <span>可多选，也可以不选</span>
        </div>
        <div className="plan3-chips">
          {INTEREST_OPTIONS.map((it) => (
            <button
              type="button"
              key={it}
              aria-pressed={form.interests.includes(it)}
              className={`plan3-chip-btn ${form.interests.includes(it) ? 'on' : ''}`}
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
      </div>

      <div className="plan3-sec">
        <div className="plan3-sec-head">
          <b>哪些内容不能被修改？</b>
          <span>例如已经购买的门票、必须去的景点，或已确定的住宿位置</span>
        </div>
        <div className="plan3-row">
          <div className="plan3-field-grow">
            <Input.TextArea
              rows={2}
              placeholder="例如：第三天下午已购买大英博物馆特展门票，请勿移动。"
              value={form.locked}
              maxLength={300}
              onChange={(e) => patch({ locked: e.target.value })}
            />
          </div>
        </div>
        <div className="plan3-sec-head plan3-sec-sub">
          <b>特别要求</b>
          <span>其它希望我记住的偏好</span>
        </div>
        <div className="plan3-row">
          <div className="plan3-field-grow">
            <Input.TextArea
              data-testid="planner-notes"
              rows={2}
              placeholder="例如：和父母同行，希望少走路；不吃辣；酒店靠近地铁。"
              value={form.notes}
              maxLength={500}
              onChange={(e) => patch({ notes: e.target.value })}
            />
          </div>
        </div>
      </div>
    </div>
  );
}
