import { useEffect, useRef } from 'react';
import * as echarts from 'echarts';
import { Empty } from 'antd';
import type { ItineraryItem } from '../../types/trip';

const TYPE_LABELS: Record<string, string> = {
  hotel: '酒店',
  attraction: '景点',
  restaurant: '餐饮',
  transport: '交通',
};

const PIE_COLORS = ['#1f6feb', '#16a34a', '#d97706', '#7c5cd6', '#d6457a', '#2f9fb8'];
const TEXT = 'rgba(28, 28, 30, 0.9)';
const TEXT_DIM = 'rgba(60, 60, 67, 0.55)';
const AXIS = '#e4e6eb';

interface Props {
  items: ItineraryItem[];
  budget?: number | null;
}

/** 预算图表：左饼图（按类型）+ 右柱图（按天），深色玻璃主题 */
export default function BudgetChart({ items, budget }: Props) {
  const containerRef = useRef<HTMLDivElement>(null);
  const chartRef = useRef<echarts.ECharts | null>(null);

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
    const dayKeys = Object.keys(byDay).map(Number).sort((a, b) => a - b);

    chart.setOption({
      backgroundColor: 'transparent',
      title: {
        text: `总花费 ¥${total.toFixed(0)}`,
        subtext: budget ? `预算 ¥${budget}（已用 ${((total / budget) * 100).toFixed(0)}%）` : undefined,
        left: 'center',
        top: 8,
        textStyle: { fontSize: 15, color: TEXT },
        subtextStyle: { color: TEXT_DIM },
      },
      tooltip: {
        trigger: 'item',
        formatter: '{b}: ¥{c}',
        backgroundColor: 'rgba(255, 255, 255, 0.96)',
  borderColor: '#e4e6eb',
        textStyle: { color: TEXT },
      },
      legend: { bottom: 0, left: '12%', textStyle: { color: TEXT_DIM }, itemStyle: { borderWidth: 0 } },
      grid: { right: '6%', top: '24%', bottom: '18%', width: '42%', containLabel: true },
      xAxis: {
        type: 'category',
        data: dayKeys.map((d) => `Day ${d}`),
        axisLine: { lineStyle: { color: AXIS } },
        axisTick: { lineStyle: { color: AXIS } },
        axisLabel: { color: TEXT_DIM },
      },
      yAxis: {
        type: 'value',
        name: '元',
        nameTextStyle: { color: TEXT_DIM },
        splitLine: { lineStyle: { color: AXIS } },
        axisLabel: { color: TEXT_DIM },
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
          label: { formatter: '{b}\n¥{c}', color: TEXT_DIM },
          color: PIE_COLORS,
          itemStyle: { borderColor: '#ffffff', borderWidth: 2 },
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
  }, [items, budget]);

  return (
    <div>
      <div ref={containerRef} style={{ width: '100%', height: 340 }} />
      {items.length === 0 && <Empty description="暂无花费数据" />}
    </div>
  );
}
