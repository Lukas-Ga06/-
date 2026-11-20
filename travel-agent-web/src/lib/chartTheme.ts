import type { ThemeMode } from '../store/themeStore';

/**
 * ECharts 主题色板。
 *
 * 为什么需要：canvas 里的文字/坐标轴读不到 CSS 变量（`--ink` 等只对 DOM 生效），
 * 所以图表必须按主题拿到一份等价色值。色值口径对齐 `styles/global.css` 的设计令牌：
 * - `text`     ↔ `--text`（#111827 / #e8eaee）
 * - `textDim`  ↔ `--text-3` 附近的次级文字（#8b93a1 / #a0a7b4）
 * - `axisLine` ↔ `--border`（#e4e6eb / #333a46），网格线用同一色
 * - `pieBorder`↔ 卡片底色（#ffffff / #161920），扇区之间靠它与背景分隔
 *
 * 改动这里等于同时改所有图表；不要在业务组件里再写死颜色。
 */
export interface ChartPalette {
  /** 主文字：标题、tooltip 正文 */
  text: string;
  /** 次级文字：副标题、图例、饼图标签 */
  textDim: string;
  /** 坐标轴标签色 */
  axisLabel: string;
  /** 坐标轴 / 网格线色 */
  axisLine: string;
  /** 图例文字色（趋势图沿用原有更深的图例色） */
  legend: string;
  /** tooltip 背景 / 描边 / 正文 */
  tooltipBg: string;
  tooltipBorder: string;
  tooltipText: string;
  /** 饼图扇区描边：与卡片底色一致 */
  pieBorder: string;
  /** 系列强调色（柱/折线） */
  bar: string;
  line: string;
}

export const CHART_PALETTES: Record<ThemeMode, ChartPalette> = {
  light: {
    text: 'rgba(28, 28, 30, 0.9)',
    textDim: 'rgba(60, 60, 67, 0.55)',
    axisLabel: '#8b93a1',
    axisLine: '#e4e6eb',
    legend: '#5b6472',
    tooltipBg: 'rgba(255, 255, 255, 0.96)',
    tooltipBorder: '#e4e6eb',
    tooltipText: 'rgba(28, 28, 30, 0.9)',
    pieBorder: '#ffffff',
    bar: '#1f6feb',
    line: '#16a34a',
  },
  dark: {
    text: '#e8eaee',
    textDim: '#a0a7b4',
    axisLabel: '#6f7885',
    axisLine: '#333a46',
    legend: '#a0a7b4',
    tooltipBg: 'rgba(27, 31, 39, 0.96)',
    tooltipBorder: '#333a46',
    tooltipText: '#e8eaee',
    pieBorder: '#161920',
    bar: '#5b9bff',
    line: '#4ade80',
  },
};

/** 按主题取色板；未知值（理论上不会出现）按浅色处理，保证图表永远有颜色 */
export function chartPalette(mode: ThemeMode): ChartPalette {
  return CHART_PALETTES[mode] ?? CHART_PALETTES.light;
}
