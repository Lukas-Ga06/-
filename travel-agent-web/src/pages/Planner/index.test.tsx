// @vitest-environment happy-dom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { App } from 'antd';
import { MemoryRouter } from 'react-router-dom';
import Planner from './index';
import { createDraft, DRAFT_STORAGE_KEY, saveDraft, type PlannerStep } from './planDraft';
import { emptyPlanForm, type PlanFormState } from './planForm';

const agent = vi.hoisted(() => ({
  send: vi.fn(),
  reset: vi.fn(),
  confirm: vi.fn(),
  cancel: vi.fn(),
  clearCommitted: vi.fn(),
  newConversation: vi.fn(),
}));

vi.mock('../../store/agentStore', () => {
  const state = {
    streaming: false,
    messages: [],
    pendingConfirm: null,
    committedTripId: null,
    send: agent.send,
    reset: agent.reset,
    confirm: agent.confirm,
    cancel: agent.cancel,
    clearCommitted: agent.clearCommitted,
    newConversation: agent.newConversation,
  };
  const useAgentStore = (selector: (s: typeof state) => unknown) => selector(state);
  return { useAgentStore: Object.assign(useAgentStore, { getState: () => state }) };
});

vi.mock('../../components/ChatPanel', () => ({
  default: () => <div data-testid="chat-panel" />,
}));
vi.mock('../../components/PlanDiff', () => ({ default: () => null }));

function renderPage(entry = '/planner') {
  return render(
    <MemoryRouter initialEntries={[entry]}>
      <App>
        <Planner />
      </App>
    </MemoryRouter>,
  );
}

const destinationInput = () => screen.getByTestId('planner-destination') as HTMLInputElement;
const notesInput = () => screen.getByTestId('planner-notes') as HTMLTextAreaElement;
const stepText = () => screen.getByTestId('planner-step-indicator').textContent ?? '';

function fillDestination(value: string) {
  fireEvent.change(destinationInput(), { target: { value } });
}

function clickNext() {
  fireEvent.click(screen.getByTestId('planner-next'));
}

function clickBack() {
  fireEvent.click(screen.getByTestId('planner-back'));
}

/** 预置一条本地草稿：草稿提示是「进入页面就有」的状态，不走填写流程更直接 */
function seedDraft(
  form: PlanFormState = { ...emptyPlanForm(), destination: '东京' },
  step: PlannerStep = 1,
) {
  saveDraft(createDraft(form, step));
}

beforeEach(() => {
  localStorage.clear();
});

afterEach(() => {
  cleanup();
  Object.values(agent).forEach((fn) => fn.mockReset());
  localStorage.clear();
});

describe('Planner · 一句话需求回填（P1-02）', () => {
  it('把目的地回填到第 1 步，不再让用户重复输入', () => {
    renderPage('/planner?prompt=帮我规划周末上海两日游，2人，预算3000元');

    expect(destinationInput().value).toBe('上海');
    // 已识别摘要直接展示，用户只需核对
    const parsed = screen.getByTestId('planner-parsed');
    expect(parsed.textContent).toContain('2 天');
    expect(parsed.textContent).toContain('2 人');
    expect(parsed.textContent).toContain('预算 ¥3000');
  });

  it('天数写进第 1 步表单，人数字段不再是默认 1 人', () => {
    renderPage('/planner?prompt=上海两日游，2人，预算3000元');

    // 天数输入框（InputNumber）值为 2：data-testid 落在 input 本身
    const days = screen.getByTestId('planner-days') as HTMLInputElement;
    expect(days.value).toBe('2');
  });

  it('天数与日期范围冲突时给出显式提示，不静默覆盖', () => {
    renderPage('/planner?prompt=上海两日游，11月21日-11月23日');

    const alert = screen.getByRole('alert');
    expect(alert.textContent).toContain('对不上');
    expect(alert.textContent).toContain('请确认');
  });

  it('低置信字段标注待确认（周末按两天粗估）', () => {
    renderPage('/planner?prompt=周末去苏州');

    expect(screen.getByTestId('planner-parsed').textContent).toContain('粗估');
  });

  it('「清除并手动填写」回到空白表单', () => {
    renderPage('/planner?prompt=上海两日游，2人，预算3000元');
    expect(destinationInput().value).toBe('上海');

    fireEvent.click(screen.getByRole('button', { name: /清除并手动填写/ }));

    expect(destinationInput().value).toBe('');
    expect(screen.queryByTestId('planner-parsed')).toBeNull();
  });

  it('解析不出结构化信息时不显示「已识别」，原话留给第 2 步的特别要求', () => {
    renderPage('/planner?prompt=酒店离地铁近一点，晚上想逛街');

    expect(screen.queryByTestId('planner-parsed')).toBeNull();
    expect(destinationInput().value).toBe('');

    // 三步流程：特别要求在第 2 步，先补上第 1 步必填的目的地与天数再继续
    fillDestination('上海');
    fireEvent.change(screen.getByTestId('planner-days'), { target: { value: '2' } });
    clickNext();
    expect(stepText()).toContain('步骤 2/3');
    expect(notesInput().value).toContain('地铁');
  });
});

describe('Planner · 三步流程与门禁', () => {
  it('第 1 步未填目的地时停在原步并提示，不发请求', () => {
    renderPage('/planner');

    clickNext();

    expect(screen.getByTestId('planner-required-hint').textContent).toContain('先填写目的地');
    expect(stepText()).toContain('步骤 1/3');
    expect(agent.send).not.toHaveBeenCalled();
  });

  it('第 1 步只填目的地、没给出行时间时提示「请填写出行时间」', () => {
    renderPage('/planner');
    fillDestination('上海');

    clickNext();

    expect(screen.getByTestId('planner-required-hint').textContent).toContain('请填写出行时间');
    expect(stepText()).toContain('步骤 1/3');
  });

  it('第 1 步 → 第 2 步 → 第 3 步，上一步可以逐级返回', () => {
    renderPage('/planner?prompt=上海三日游');

    clickNext(); // 天数已有，第 1 步门禁放行
    expect(stepText()).toContain('步骤 2/3');
    expect(screen.getByTestId('planner-notes')).toBeTruthy();

    clickNext(); // 一句话需求默认「从零规划」，第 2 步可整段跳过
    expect(stepText()).toContain('步骤 3/3');
    expect(screen.getByTestId('planner-summary')).toBeTruthy();

    clickBack();
    expect(stepText()).toContain('步骤 2/3');

    clickBack();
    expect(stepText()).toContain('步骤 1/3');
    expect(destinationInput().value).toBe('上海');
  });

  it('第 3 步展示结构化摘要', () => {
    renderPage('/planner?prompt=帮我规划周末上海两日游，2人，预算3000元');
    clickNext();
    clickNext();

    const summary = screen.getByTestId('planner-summary');
    expect(summary.textContent).toContain('上海');
    expect(summary.textContent).toContain('计划玩 2 天');
    expect(summary.textContent).toContain('¥3000');
    expect(summary.textContent).toContain('张弛有度');
    // 一句话需求走「从零规划」，摘要里要如实写出来
    expect(summary.textContent).toContain('从零规划');
  });

  it('攻略模式没有正文时不能进入第 3 步，补齐后才能继续', () => {
    renderPage('/planner?prompt=上海三日游');
    clickNext(); // 第 2 步

    fireEvent.click(screen.getByRole('button', { name: /我已经收藏了攻略/ }));
    clickNext();

    expect(stepText()).toContain('步骤 2/3');
    expect(screen.getByText(/攻略模式至少需要一篇攻略正文/)).toBeTruthy();

    fireEvent.change(screen.getByPlaceholderText('粘贴攻略正文……'), {
      target: { value: '第一天外滩，第二天迪士尼。' },
    });
    clickNext();

    expect(stepText()).toContain('步骤 3/3');
  });

  it('第 3 步点击生成计划：走 agentStore.send 并进入规划工作区', () => {
    renderPage('/planner?prompt=上海三日游');
    clickNext();
    clickNext();

    fireEvent.click(screen.getByTestId('planner-submit'));

    expect(agent.send).toHaveBeenCalledTimes(1);
    expect(agent.send).toHaveBeenCalledWith(
      expect.objectContaining({
        transport: 'post',
        message: expect.stringContaining('目的地：上海'),
        displayText: expect.stringContaining('结构化需求提交'),
      }),
    );
    expect(screen.getByTestId('chat-panel')).toBeTruthy();
    // 需求已经提交，草稿不该再留着让下次进入时提示恢复
    expect(localStorage.getItem(DRAFT_STORAGE_KEY)).toBeNull();
  });

  it('「保存草稿」按钮立即写入本地草稿', () => {
    renderPage('/planner');
    fillDestination('巴黎');

    fireEvent.click(screen.getByTestId('planner-save-draft'));

    expect(localStorage.getItem(DRAFT_STORAGE_KEY)).toContain('巴黎');
  });
});

describe('Planner · 草稿恢复（P1-03）', () => {
  it('自动保存草稿，重新进入时提示恢复，确认后回填且不静默覆盖表单', async () => {
    const first = renderPage('/planner');
    fillDestination('东京');

    await waitFor(
      () => {
        expect(localStorage.getItem(DRAFT_STORAGE_KEY)).toContain('东京');
      },
      { timeout: 3000 },
    );
    first.unmount();

    renderPage('/planner');

    // 只提示、不自动恢复：当前表单仍是空的
    expect(destinationInput().value).toBe('');
    fireEvent.click(screen.getByTestId('planner-restore-draft'));

    expect(destinationInput().value).toBe('东京');
    expect(screen.queryByTestId('planner-restore-draft')).toBeNull();
  });

  it('「忽略」清除本地草稿，不把旧草稿填进当前表单', () => {
    seedDraft();
    renderPage('/planner');

    fireEvent.click(screen.getByTestId('planner-discard-draft'));

    expect(localStorage.getItem(DRAFT_STORAGE_KEY)).toBeNull();
    expect(screen.queryByTestId('planner-restore-draft')).toBeNull();
    expect(destinationInput().value).toBe('');
  });

  it('?prompt= 打开时不自动恢复草稿，以一句话需求为准；恢复时才用草稿', () => {
    seedDraft({ ...emptyPlanForm(), destination: '东京', days: 3, notes: '不想走太多路' }, 2);

    renderPage('/planner?prompt=上海两日游');

    // 一句话需求优先，草稿只作为可选项提示
    expect(destinationInput().value).toBe('上海');
    expect(screen.getByTestId('planner-parsed')).toBeTruthy();

    fireEvent.click(screen.getByTestId('planner-restore-draft'));

    expect(stepText()).toContain('步骤 2/3');
    expect(screen.queryByTestId('planner-parsed')).toBeNull();
    expect(notesInput().value).toBe('不想走太多路');
    clickBack();
    expect(destinationInput().value).toBe('东京');
  });
});
