// @vitest-environment happy-dom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, render, waitFor } from '@testing-library/react';
import { App as AntApp } from 'antd';
import { CHART_PALETTES } from '../../lib/chartTheme';
import { useThemeStore } from '../../store/themeStore';
import ExpensePanel from './index';

/**
 * 记账页饼图同样是 canvas：文字/标签颜色必须由组件按主题写进 option。
 * mock 掉 lib/echarts 与接口，只断言 option，不真渲染 canvas、不发网络请求。
 */
const echartsMock = vi.hoisted(() => {
  const setOption = vi.fn();
  const clear = vi.fn();
  const resize = vi.fn();
  const dispose = vi.fn();
  const init = vi.fn(() => ({ setOption, clear, resize, dispose }));
  return { setOption, clear, resize, dispose, init };
});

vi.mock('../../lib/echarts', () => ({
  echarts: {
    init: echartsMock.init,
    graphic: { LinearGradient: class {} },
  },
}));

const expenses = [
  { id: 1, tripId: 1, category: '餐饮', amount: 120, spentAt: '2026-11-19' },
  { id: 2, tripId: 1, category: '交通', amount: 80, spentAt: '2026-11-19' },
];

vi.mock('../../api/expense', () => ({
  getExpensesPage: vi.fn(async () => ({ items: expenses, pagination: null })),
  addExpense: vi.fn(async () => ({})),
  deleteExpense: vi.fn(async () => undefined),
}));

vi.mock('../../api/trip', () => ({
  getBudgetExecution: vi.fn(async () => null),
}));

function lastOption(): any {
  const calls = echartsMock.setOption.mock.calls;
  return calls[calls.length - 1]?.[0];
}

describe('ExpensePanel 饼图主题配色', () => {
  beforeEach(() => {
    echartsMock.setOption.mockClear();
    echartsMock.init.mockClear();
    useThemeStore.setState({ mode: 'light' });
  });

  afterEach(cleanup);

  it('浅色/暗色下饼图标签与扇区描边不同且来自主题，tooltip 深底浅字可读', async () => {
    render(
      <AntApp>
        <ExpensePanel tripId={1} budget={1000} planned={500} />
      </AntApp>,
    );
    await waitFor(() => expect(echartsMock.setOption).toHaveBeenCalled());
    const light = lastOption();

    act(() => {
      useThemeStore.getState().setMode('dark');
    });
    await waitFor(() => expect(echartsMock.setOption.mock.calls.length).toBeGreaterThan(1));
    const dark = lastOption();

    // 标签颜色来自主题色板（暗色不能继续用浅色下的 rgba 深灰）
    expect(light.series[0].label.color).toBe(CHART_PALETTES.light.textDim);
    expect(dark.series[0].label.color).toBe(CHART_PALETTES.dark.textDim);
    expect(dark.series[0].label.color).not.toBe(light.series[0].label.color);

    // 扇区描边跟随卡片底色，暗色下不能是白色描边
    expect(light.series[0].itemStyle.borderColor).toBe(CHART_PALETTES.light.pieBorder);
    expect(dark.series[0].itemStyle.borderColor).toBe(CHART_PALETTES.dark.pieBorder);

    // tooltip 是固定深色底：正文必须是浅色（两套主题都一样可读）
    expect(light.tooltip.textStyle.color).toBe('#f2f5fa');
    expect(dark.tooltip.textStyle.color).toBe('#f2f5fa');
  });
});