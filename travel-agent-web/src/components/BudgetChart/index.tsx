import { useEffect, useMemo, useRef } from 'react';
import { echarts, type EChartsType } from '../../lib/echarts';
import { Empty } from 'antd';
import type { ItineraryItem } from '../../types/trip';
import { useThemeStore } from '../../store/themeStore';
import { chartPalette } from '../../lib/chartTheme';

const TYPE_LABELS: Record<string, string> = {
  hotel: '酒店',
  attraction: '景点',
  restaurant: '餐饮',
  transport: '交通',
};

const PIE_COLORS = ['#1f6feb', '#16a34a', '#d97706', '#7c5cd6', '#d6457a', '#2f9fb8'];

interface Props {
  items: ItineraryItem[];
  budget?: number | null;
}

/** 预算图表：左饼图（按类型）+ 右柱图（按天）；文字/坐标轴颜色跟随主题 */
export default function BudgetChart({ items, budget }: Props) {
  const containerRef = useRef<HTMLDivElement>(null);
  const chartRef = useRef<EChartsType | null>(null);
  const themeMode = useThemeStore((s) => s.mode);
  // canvas 读不到 CSS 变量，按主题取等价色值（见 lib/chartTheme）
  const palette = useMemo(() => chartPalette(themeMode), [themeMode]);

  useEffect(() => {
    if (!containerRef.current) return;
    const chart = echarts.init(containerRef.current);
    chartRef.current = chart;
    const onResize = () => chart.resize();
    window.addEventListener('resize', onResize);
    return () => {
      window.removeEventListener('resize', onResize);
      chart.dispose();
      chartRef.current = null;
    };
  }, []);

  useEffect(() => {
    const chart = chartRef.current;
    if (!chart) return;
    const withCost = items.filter((i) => (i.cost ?? 0) > 0);
    if (withCost.length === 0) {
      chart.clear();
      return;
    }
    const total = withCost.reduce((s, i) => s + (i.cost ?? 0), 0);

    const byType: Record<string, number> = {};
    const byDay: Record<number, number> = {};
    withCost.forEach((i) => {
      const label = TYPE_LABELS[i.itemType] ?? i.itemType;
      byType[label] = (byType[label] ?? 0) + (i.cost ?? 0);
      byDay[i.dayIndex] = (byDay[i.dayIndex] ?? 0) + (i.cost ?? 0);
    });
    const dayKeys = Object.keys(byDay)
      .map(Number)
      .sort((a, b) => a - b);

    chart.setOption({
      backgroundColor: 'transparent',
      title: {
        text: `总花费 ¥${total.toFixed(0)}`,
        subtext: budget
          ? `预算 ¥${budget}（已用 ${((total / budget) * 100).toFixed(0)}%）`
          : undefined,
        left: 'center',
        top: 8,
        textStyle: { fontSize: 15, color: palette.text },
        subtextStyle: { color: palette.textDim },
      },
      tooltip: {
        trigger: 'item',
        formatter: '{b}: ¥{c}',
        backgroundColor: palette.tooltipBg,
        borderColor: palette.tooltipBorder,
        textStyle: { color: palette.tooltipText },
      },
      legend: {
        bottom: 0,
        left: '12%',
        textStyle: { color: palette.textDim },
        itemStyle: { borderWidth: 0 },
      },
      grid: { right: '6%', top: '24%', bottom: '18%', width: '42%', containLabel: true },
      xAxis: {
        type: 'category',
        data: dayKeys.map((d) => `Day ${d}`),
        axisLine: { lineStyle: { color: palette.axisLine } },
        axisTick: { lineStyle: { color: palette.axisLine } },
        axisLabel: { color: palette.textDim },
      },
      yAxis: {
        type: 'value',
        name: '元',
        nameTextStyle: { color: palette.textDim },
        splitLine: { lineStyle: { color: palette.axisLine } },
        axisLabel: { color: palette.textDim },
      },
      series: [
        {
          name: '费用构成',
          type: 'pie',
          radius: ['28%', '52%'],
          center: ['26%', '56%'],
          data: Object.entries(byType).map(([name, value]) => ({
            name,
            value: Number(value.toFixed(2)),
          })),
          label: { formatter: '{b}\n¥{c}', color: palette.textDim },
          color: PIE_COLORS,
          itemStyle: { borderColor: palette.pieBorder, borderWidth: 2 },
        },
        {
          name: '每日花费',
          type: 'bar',
          data: dayKeys.map((d) => Number((byDay[d] ?? 0).toFixed(2))),
          itemStyle: {
            color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
              { offset: 0, color: '#1f6feb' },
              { offset: 1, color: '#5ac8fa' },
            ]),
            borderRadius: [5, 5, 0, 0],
          },
          barWidth: 28,
        },
      ],
    });
    // 主题切换时必须用新色板重画（canvas 不跟随 CSS 变量）
  }, [items, budget, palette]);

  return (
    <div>
      <div ref={containerRef} style={{ width: '100%', height: 340 }} />
      {items.length === 0 && <Empty description="暂无花费数据" />}
    </div>
  );
}
