// @vitest-environment happy-dom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, render } from '@testing-library/react';
import { CHART_PALETTES } from '../../lib/chartTheme';
import { useThemeStore } from '../../store/themeStore';
import BudgetChart from './index';
import type { ItineraryItem } from '../../types/trip';

/**
 * 图表颜色回归：canvas 读不到 CSS 变量，必须由组件按主题把色值写进 option。
 * 这里 mock 掉 lib/echarts（不真的初始化 canvas），断言「传给 ECharts 的 option」。
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
    graphic: {
      LinearGradient: class {
        constructor(
          public x: number,
          public y: number,
          public x2: number,
          public y2: number,
          public colorStops: unknown,
        ) {}
      },
    },
  },
}));

function item(patch: Partial<ItineraryItem> = {}): ItineraryItem {
  return {
    id: 1,
    dayIndex: 1,
    itemType: 'hotel',
    title: '外滩酒店',
    startTime: '09:00',
    endTime: '10:00',
    latitude: null,
    longitude: null,
    cost: 600,
    ...patch,
  };
}

/** 取最近一次 setOption 的 option（断言的是真实传给 ECharts 的对象） */
function lastOption(): any {
  const calls = echartsMock.setOption.mock.calls;
  return calls[calls.length - 1]?.[0];
}

describe('BudgetChart 主题配色', () => {
  beforeEach(() => {
    echartsMock.setOption.mockClear();
    echartsMock.init.mockClear();
    useThemeStore.setState({ mode: 'light' });
  });

  afterEach(() => {
    cleanup();
  });

  it('浅色与暗色主题下文字/坐标轴/饼图描边颜色不同且来自主题色板', () => {
    const items = [item(), item({ id: 2, itemType: 'restaurant', cost: 200, dayIndex: 2 })];

    const { rerender } = render(<BudgetChart items={items} budget={1200} />);
    const light = lastOption();
    expect(light).toBeTruthy();

    act(() => {
      useThemeStore.getState().setMode('dark');
    });
    rerender(<BudgetChart items={items} budget={1200} />);
    const dark = lastOption();
    expect(dark).toBeTruthy();
    expect(echartsMock.setOption.mock.calls.length).toBeGreaterThanOrEqual(2);

    // 取自主题色板，而不是组件里写死的某一套
    expect(light.title.textStyle.color).toBe(CHART_PALETTES.light.text);
    expect(dark.title.textStyle.color).toBe(CHART_PALETTES.dark.text);
    expect(light.title.subtextStyle.color).toBe(CHART_PALETTES.light.textDim);
    expect(dark.title.subtextStyle.color).toBe(CHART_PALETTES.dark.textDim);

    // 坐标轴文字与网格线：暗色下不能仍是浅色值
    expect(light.xAxis.axisLabel.color).toBe(CHART_PALETTES.light.textDim);
    expect(dark.xAxis.axisLabel.color).toBe(CHART_PALETTES.dark.textDim);
    expect(light.yAxis.splitLine.lineStyle.color).toBe(CHART_PALETTES.light.axisLine);
    expect(dark.yAxis.splitLine.lineStyle.color).toBe(CHART_PALETTES.dark.axisLine);

    // tooltip 与饼图扇区描边也跟随主题
    expect(light.tooltip.backgroundColor).toBe(CHART_PALETTES.light.tooltipBg);
    expect(dark.tooltip.backgroundColor).toBe(CHART_PALETTES.dark.tooltipBg);
    expect(light.series[0].itemStyle.borderColor).toBe(CHART_PALETTES.light.pieBorder);
    expect(dark.series[0].itemStyle.borderColor).toBe(CHART_PALETTES.dark.pieBorder);

    // 关键回归点：两套主题不能给出同一组文字/轴线颜色
    expect(dark.title.textStyle.color).not.toBe(light.title.textStyle.color);
    expect(dark.xAxis.axisLabel.color).not.toBe(light.xAxis.axisLabel.color);
    expect(dark.yAxis.splitLine.lineStyle.color).not.toBe(light.yAxis.splitLine.lineStyle.color);
    expect(dark.tooltip.backgroundColor).not.toBe(light.tooltip.backgroundColor);
    expect(dark.series[0].itemStyle.borderColor).not.toBe(light.series[0].itemStyle.borderColor);
  });

  it('暗色下主文字与卡片底色有足够对比度（不是深色叠深色）', () => {
    const items = [item()];
    act(() => {
      useThemeStore.getState().setMode('dark');
    });
    render(<BudgetChart items={items} budget={1200} />);
    const option = lastOption();
    const parseRgb = (color: string): number[] => {
      const hex = color.match(/^#([0-9a-f]{3}|[0-9a-f]{6})$/i);
      if (hex) {
        const raw =
          hex[1].length === 3
            ? hex[1]
                .split('')
                .map((c) => c + c)
                .join('')
            : hex[1];
        return [0, 2, 4].map((i) => parseInt(raw.slice(i, i + 2), 16));
      }
      return color
        .match(/[\d.]+/g)!
        .slice(0, 3)
        .map(Number);
    };
    const luminance = (rgb: number[]) => {
      const linear = rgb.map((value) => {
        const c = value / 255;
        return c <= 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4;
      });
      return 0.2126 * linear[0] + 0.7152 * linear[1] + 0.0722 * linear[2];
    };
    const textLum = luminance(parseRgb(option.title.textStyle.color));
    // 暗色卡片底色 #161920
    const cardLum = luminance([22, 25, 32]);
    const contrast = (Math.max(textLum, cardLum) + 0.05) / (Math.min(textLum, cardLum) + 0.05);
    // WCAG AA 正文阈值 4.5:1；暗色主题的标题文字必须超过它
    expect(contrast).toBeGreaterThan(4.5);
  });
});
