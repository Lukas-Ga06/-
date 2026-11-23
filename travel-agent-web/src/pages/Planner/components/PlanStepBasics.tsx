import { Button, DatePicker, Input, InputNumber, Radio } from 'antd';
import { WarningOutlined } from '@ant-design/icons';
import dayjs, { type Dayjs } from 'dayjs';
import type { CompanionType, PlanFormState } from '../planForm';
import { describeParsed, lowConfidenceHints, type ParsedPlanPrompt } from '../parsePrompt';
import type { PatchPlanForm } from './types';

const COMPANION_CHIPS: { key: CompanionType; label: string }[] = [
  { key: 'alone', label: '独自' },
  { key: 'partner', label: '伴侣' },
  { key: 'friends', label: '朋友' },
  { key: 'parents', label: '带父母' },
  { key: 'kids', label: '带儿童' },
  { key: 'other', label: '其他' },
];

interface Props {
  form: PlanFormState;
  patch: PatchPlanForm;
  /** 一句话需求解析出的结构化结果；没有或没识别出字段时为 null */
  parsedPrompt: ParsedPlanPrompt | null;
  /** 「清除并手动填写」：清掉识别结果，回到空白表单（由页面执行） */
  onClearParsed: () => void;
}

/**
 * 第 1 步：基本信息（唯一必填步骤）。
 * 顶部是「一句话需求」的已识别字段摘要，正文只问目的地、出行时间、同行人员。
 */
export default function PlanStepBasics({ form, patch, parsedPrompt, onClearParsed }: Props) {
  const timeMode = form.startDate && form.endDate ? 'dates' : 'days';
  const parsedParts = parsedPrompt ? describeParsed(parsedPrompt) : [];
  const parsedHints = parsedPrompt ? lowConfidenceHints(parsedPrompt) : [];

  return (
    <div className="plan3-step-body">
      {parsedPrompt?.matched && (
        <div className="plan3-parsed" data-testid="planner-parsed">
          <div className="plan3-parsed-head">
            <b>已识别</b>
            <span>这些字段已经填好，核对或修改后继续</span>
            <span className="plan3-spacer" />
            <Button size="small" type="text" onClick={onClearParsed}>
              清除并手动填写
            </Button>
          </div>
          <div className="plan3-chips">
            {parsedParts.map((part) => (
              <span key={part} className="plan3-chip">
                {part}
              </span>
            ))}
          </div>
          {parsedHints.length > 0 && (
            <ul className="plan3-hints">
              {parsedHints.map((hint) => (
                <li key={hint}>
                  <WarningOutlined aria-hidden="true" /> {hint}
                </li>
              ))}
            </ul>
          )}
          {parsedPrompt.conflicts.map((conflict) => (
            <div className="plan3-conflict" role="alert" key={conflict}>
              {conflict}
            </div>
          ))}
        </div>
      )}

      <div className="plan3-sec">
        <div className="plan3-sec-head">
          <b>目的地</b>
          <span className="plan3-required">必填</span>
          <span>城市或地区，例如「东京」「云南大理」</span>
        </div>
        <div className="plan3-row">
          <div className="plan3-field-grow">
            <Input
              data-testid="planner-destination"
              placeholder="目的地，例如：东京、巴黎、伦敦"
              value={form.destination}
              maxLength={60}
              onChange={(e) => patch({ destination: e.target.value })}
            />
          </div>
        </div>
      </div>

      <div className="plan3-sec">
        <div className="plan3-sec-head">
          <b>出行时间</b>
          <span className="plan3-required">必填</span>
          <span>旅行天数或具体日期，至少给一个</span>
        </div>
        <div className="plan3-row">
          <Radio.Group
            value={timeMode}
            onChange={(e) =>
              patch(
                e.target.value === 'dates'
                  ? {
                      days: null,
                      startDate: form.startDate ?? dayjs().format('YYYY-MM-DD'),
                      endDate: form.endDate ?? dayjs().add(2, 'day').format('YYYY-MM-DD'),
                    }
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
              data-testid="planner-days"
              min={1}
              max={30}
              value={form.days ?? undefined}
              placeholder="几天？"
              addonAfter="天"
              className="plan3-num"
              onChange={(v) => patch({ days: v ?? null })}
            />
          ) : (
            <DatePicker.RangePicker
              value={
                form.startDate && form.endDate
                  ? ([dayjs(form.startDate), dayjs(form.endDate)] as [Dayjs, Dayjs])
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

      <div className="plan3-sec">
        <div className="plan3-sec-head">
          <b>同行人员</b>
          <span>用于判断节奏、房型和活动适配度</span>
        </div>
        <div className="plan3-chips">
          {COMPANION_CHIPS.map((c) => (
            <button
              type="button"
              key={c.key}
              aria-pressed={form.companion === c.key}
              className={`plan3-chip-btn ${form.companion === c.key ? 'on' : ''}`}
              onClick={() => patch({ companion: c.key })}
            >
              {c.label}
            </button>
          ))}
        </div>
        <div className="plan3-row">
          <span className="plan3-label">成人</span>
          <InputNumber
            data-testid="planner-adults"
            min={1}
            max={20}
            value={form.adults}
            className="plan3-num-sm"
            onChange={(v) => patch({ adults: v ?? 1 })}
          />
          <span className="plan3-label">儿童</span>
          <InputNumber
            min={0}
            max={20}
            value={form.kids}
            className="plan3-num-sm"
            onChange={(v) => patch({ kids: v ?? 0 })}
          />
        </div>
      </div>
    </div>
  );
}
