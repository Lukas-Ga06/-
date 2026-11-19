import { useEffect, useRef, useState } from 'react';
import { App, Button, DatePicker, Empty, InputNumber, Spin } from 'antd';
import { PlusOutlined } from '@ant-design/icons';
import dayjs, { Dayjs } from 'dayjs';
import { echarts, type EChartsType } from '../../lib/echarts';
import AppShell from '../../components/AppShell';
import { useThemeStore } from '../../store/themeStore';
import {
  healthSummary, listHealth, seedHealthDemo, upsertHealth,
  type HealthRecord, type HealthSummary,
} from '../../api/assistant';

/** 健康模块：小米手环数据导入/记录 + 7 天趋势 + 建议 */
export default function Health() {
  const { message } = App.useApp();
  const [records, setRecords] = useState<HealthRecord[]>([]);
  const [summary, setSummary] = useState<HealthSummary | null>(null);
  const [loading, setLoading] = useState(true);
  const [seeding, setSeeding] = useState(false);
  const [form, setForm] = useState<{ date: Dayjs; steps?: number; sleepHours?: number; heartRate?: number; weight?: number }>({
    date: dayjs(),
  });
  const chartElRef = useRef<HTMLDivElement>(null);
  const chartRef = useRef<EChartsType | null>(null);
  const themeMode = useThemeStore((s) => s.mode);
  // echarts 画在 canvas 上，读不到 CSS 变量，这里按主题给一组等价色值
  const dark = themeMode === 'dark';
  const chartColors = dark
    ? { axis: '#6f7885', grid: '#333a46', bar: '#5b9bff', line: '#4ade80', legend: '#a0a7b4' }
    : { axis: '#8b93a1', grid: '#e4e6eb', bar: '#1f6feb', line: '#16a34a', legend: '#5b6472' };

  const load = async () => {
    try {
      const [recs, sum] = await Promise.all([listHealth(), healthSummary()]);
      setRecords(recs);
      setSummary(sum);
    } catch (e: any) {
      message.error(e.message);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { load(); }, []);

  // 趋势图：容器在 loading 结束（数据到达）之后才挂载，
  // 因此初始化必须依赖 loading，否则首次渲染时 ref 还是 null，图表永远不会创建。
  useEffect(() => {
    if (loading || !chartElRef.current) return;
    const chart = echarts.init(chartElRef.current);
    chartRef.current = chart;
    const onResize = () => chart.resize();
    window.addEventListener('resize', onResize);
    return () => {
      window.removeEventListener('resize', onResize);
      chart.dispose();
      chartRef.current = null;
    };
  }, [loading]);

  useEffect(() => {
    const chart = chartRef.current;
    if (!chart) return;
    const days = records.slice().sort((a, b) => a.date.localeCompare(b.date));
    if (days.length === 0) { chart.clear(); return; }
    chart.setOption({
      backgroundColor: 'transparent',
      tooltip: { trigger: 'axis' },
      legend: { top: 0, textStyle: { color: chartColors.legend } },
      grid: { left: 46, right: 46, top: 34, bottom: 26 },
      xAxis: { type: 'category', data: days.map((d) => d.date.slice(5)), axisLabel: { color: chartColors.axis }, axisLine: { lineStyle: { color: chartColors.grid } } },
      yAxis: [
        { type: 'value', name: '步数', splitLine: { lineStyle: { color: chartColors.grid } }, axisLabel: { color: chartColors.axis } },
        { type: 'value', name: '小时', splitLine: { show: false }, axisLabel: { color: chartColors.axis } },
      ],
      series: [
        {
          name: '步数', type: 'bar', data: days.map((d) => d.steps ?? 0), barWidth: 18,
          itemStyle: { color: chartColors.bar, borderRadius: [4, 4, 0, 0] },
        },
        {
          name: '睡眠(小时)', type: 'line', yAxisIndex: 1,
          data: days.map((d) => d.sleepHours ?? 0),
          smooth: true, symbolSize: 7,
        lineStyle: { color: chartColors.line, width: 2.5 },
        itemStyle: { color: chartColors.line },
        },
      ],
    });
    // 主题切换时需要用新的坐标轴/系列配色重画
  }, [records, themeMode]);

  const seed = async () => {
    setSeeding(true);
    try {
      const r = await seedHealthDemo();
      message.success(`已导入 ${r.created} 天演示数据`);
      load();
    } catch (e: any) {
      message.error(e.message);
    } finally {
      setSeeding(false);
    }
  };

  const submit = async () => {
    try {
      await upsertHealth({
        date: form.date.format('YYYY-MM-DD'),
        steps: form.steps ?? null,
        sleepHours: form.sleepHours ?? null,
        heartRate: form.heartRate ?? null,
        weight: form.weight ?? null,
      });
      message.success('已记录');
      setForm({ date: dayjs() });
      load();
    } catch (e: any) {
      message.error(e.message);
    }
  };

  const latest = summary?.latest;

  return (
    <AppShell active="health">
      <div className="dash-wrap">
        <div className="dash-greeting">
          <div>
            <h1>❤️ 健康数据</h1>
            <div className="dg-date">
              小米手环等设备数据导入 / 手动记录 · 7 天趋势 · 智能建议（真实设备同步需在小米运动健康 App 中导出数据）
            </div>
          </div>
        </div>

        {loading ? (
          <Spin style={{ display: 'block', margin: '60px auto' }} />
        ) : (
          <>
            <div className="stat-row" style={{ gridTemplateColumns: 'repeat(4, 1fr)', marginBottom: 4 }}>
              <div className="stat-card"><div className="sc-icon blue">👟</div><div className="sc-num">{latest?.steps ?? '-'}</div><div className="sc-label">今日步数</div></div>
              <div className="stat-card"><div className="sc-icon green">🌙</div><div className="sc-num">{latest?.sleepHours ?? '-'}</div><div className="sc-label">今日睡眠(小时)</div></div>
              <div className="stat-card"><div className="sc-icon orange">❤️</div><div className="sc-num">{latest?.restingHeartRate ?? '-'}</div><div className="sc-label">静息心率</div></div>
              <div className="stat-card"><div className="sc-icon purple">⚖️</div><div className="sc-num">{latest?.weight ?? '-'}</div><div className="sc-label">体重(kg)</div></div>
            </div>

            <div className="dash-card span2">
              <div className="dc-head">
                <b>近 7 天趋势</b>
                <span style={{ fontSize: 12, color: 'var(--ink-3)' }}>
                  {summary?.advice ?? '暂无数据'}（近 {summary?.days ?? 0} 天均值：{summary?.avgSteps ?? 0} 步 / {summary?.avgSleep ?? 0} 小时睡眠）
                </span>
              </div>
              <div ref={chartElRef} style={{ width: '100%', height: 280 }} />
            </div>

            <div className="dash-card span2">
              <div className="dc-head">
                <b>记录 / 导入数据</b>
              </div>
              <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
                <DatePicker value={form.date} onChange={(d) => d && setForm((p) => ({ ...p, date: d }))} />
                <InputNumber placeholder="步数" min={0} value={form.steps} onChange={(v) => setForm((p) => ({ ...p, steps: v ?? undefined }))} style={{ width: 110 }} />
                <InputNumber placeholder="睡眠(小时)" min={0} max={16} step={0.5} value={form.sleepHours} onChange={(v) => setForm((p) => ({ ...p, sleepHours: v ?? undefined }))} style={{ width: 130 }} />
                <InputNumber placeholder="静息心率" min={30} max={160} value={form.heartRate} onChange={(v) => setForm((p) => ({ ...p, heartRate: v ?? undefined }))} style={{ width: 120 }} />
                <InputNumber placeholder="体重(kg)" min={20} max={200} step={0.1} value={form.weight} onChange={(v) => setForm((p) => ({ ...p, weight: v ?? undefined }))} style={{ width: 110 }} />
                <Button type="primary" icon={<PlusOutlined />} onClick={submit}>保存</Button>
                <Button onClick={seed} loading={seeding}>导入小米手环演示数据（7 天）</Button>
              </div>
              <div style={{ fontSize: 11.5, color: 'var(--ink-3)', marginTop: 8 }}>
                真实设备同步说明：小米手环数据可从小米运动健康 App 导出后按上述字段录入；接入 Zepp/小米开放 API 需要账号授权。
              </div>
            </div>
          </>
        )}
      </div>
    </AppShell>
  );
}
