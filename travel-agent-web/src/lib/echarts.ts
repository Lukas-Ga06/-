/**
 * 全仓唯一的 ECharts 入口：`echarts/core` + 按需 `use()` 注册。
 *
 * 背景：之前三个图表文件都是 `import * as echarts from 'echarts'`。全量入口会把
 * 所有图表类型 / 组件 / 渲染器都打进 bundle（实测 echarts chunk ≈ 1.06 MB），
 * 而项目只用到柱、折、饼三种图。这里只注册真正用到的模块，
 * 让 Rollup 能把没用到的图表（radar / map / sankey / treemap / 3D…）彻底摇掉。
 *
 * 约定：业务文件不要再直接 `import 'echarts'`，需要新图表/组件时**在这里追加**，
 * 否则要么被摇掉（图表空白），要么把全量包又带回来。
 *
 * 类型：`echarts/core` 不导出全量 `EChartsOption`（那是 all 入口的宽松类型），
 * 所以用 `ComposeOption` 组合已注册模块的 option 类型；实例类型用 `EChartsType`。
 */
import * as echarts from 'echarts/core';
import { BarChart, LineChart, PieChart } from 'echarts/charts';
import {
  GridComponent,
  LegendComponent,
  TitleComponent,
  TooltipComponent,
} from 'echarts/components';
import { CanvasRenderer } from 'echarts/renderers';
import { LegacyGridContainLabel } from 'echarts/features';
import type { BarSeriesOption, LineSeriesOption, PieSeriesOption } from 'echarts/charts';
import type {
  GridComponentOption,
  LegendComponentOption,
  TitleComponentOption,
  TooltipComponentOption,
} from 'echarts/components';
import type { ComposeOption } from 'echarts/core';

echarts.use([
  // 图表（Health 的柱+折线、BudgetChart 的饼+柱、ExpensePanel 的饼）
  BarChart,
  LineChart,
  PieChart,
  // 组件：Title/Grid/Legend/Tooltip 已够覆盖现有三处 setOption 的配置项；
  // 坐标轴随 GridComponent 一起注册，Tooltip/Grid 内部会带上 AxisPointer。
  TitleComponent,
  GridComponent,
  LegendComponent,
  TooltipComponent,
  // 渲染器：项目全部用 canvas（没有 SSR / SVG 需求）
  CanvasRenderer,
  // BudgetChart 的 grid 用了 `containLabel: true`；ECharts 6 把它移到了 legacy 特性里，
  // 不注册就会告警并**静默失效**（饼图/柱图布局会错位）。全量入口是自带这个特性的，
  // 按需注册必须显式补上，才能保持迁移前后的视觉一致。
  LegacyGridContainLabel,
]);

/** 本项目实际用到的 ECharts option 类型（按需组合，避免依赖全量 option 类型） */
export type ECOption = ComposeOption<
  | BarSeriesOption
  | LineSeriesOption
  | PieSeriesOption
  | TitleComponentOption
  | GridComponentOption
  | LegendComponentOption
  | TooltipComponentOption
>;

export type { EChartsType } from 'echarts/core';

/** 已注册好所需模块的 echarts 实例命名空间（`init` / `graphic` 等） */
export { echarts };
export default echarts;