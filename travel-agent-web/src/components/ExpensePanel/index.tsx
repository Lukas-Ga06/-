import { useEffect, useMemo, useRef, useState } from 'react';
import {
  App,
  Button,
  DatePicker,
  Empty,
  Form,
  Input,
  InputNumber,
  List,
  Popconfirm,
  Progress,
  Select,
} from 'antd';
import { DeleteOutlined, PlusOutlined } from '@ant-design/icons';
import dayjs, { Dayjs } from 'dayjs';
import { echarts, type EChartsType } from '../../lib/echarts';
import { addExpense, deleteExpense, getExpensesPage } from '../../api/expense';
import { getBudgetExecution } from '../../api/trip';
import { DEFAULT_PAGE_SIZE } from '../../api/client';
import PaginationBar from '../PaginationBar';
import {
  CATEGORY_COLORS,
  EXPENSE_CATEGORIES,
  PAYMENT_COLORS,
  PAYMENT_METHODS,
  type Expense,
} from '../../types/expense';
import { useThemeStore } from '../../store/themeStore';
import { chartPalette } from '../../lib/chartTheme';

/** tooltip 无论主题都是深色底，正文必须用浅色（不能用主题的深色 text） */
const TOOLTIP_TEXT_ON_DARK = '#f2f5fa';

interface Props {
  tripId: number;
  budget: number | null;
  planned: number; // 行程预估花费
}

interface ExpenseSummary {
  total: number;
  byCategory: Record<string, number>;
}

/** 本页合计（服务端预算执行拿不到时的兜底口径） */
function summarize(items: Expense[]): ExpenseSummary {
  const byCategory: Record<string, number> = {};
  let total = 0;
  items.forEach((i) => {
    byCategory[i.category] = (byCategory[i.category] ?? 0) + (i.amount ?? 0);
    total += i.amount ?? 0;
  });
  return { total, byCategory };
}

/** 行程记账面板：实际支出记录 + 分类统计 + 预算对比 */
export default function ExpensePanel({ tripId, budget, planned }: Props) {
  const { message } = App.useApp();
  const [items, setItems] = useState<Expense[]>([]);
  const [summary, setSummary] = useState<ExpenseSummary>({ total: 0, byCategory: {} });
  const [loading, setLoading] = useState(false);
  /** 翻页/静默刷新的请求态：只禁用翻页按钮，不动列表（保留滚动位置与当前行） */
  const [paging, setPaging] = useState(false);
  const [offset, setOffset] = useState(0);
  const [meta, setMeta] = useState({
    total: 0,
    hasMore: false,
    paged: false,
    limit: DEFAULT_PAGE_SIZE,
  });
  const [submitting, setSubmitting] = useState(false);
  const [form] = Form.useForm();
  const chartElRef = useRef<HTMLDivElement>(null);
  const chartRef = useRef<EChartsType | null>(null);
  const themeMode = useThemeStore((s) => s.mode);
  // canvas 读不到 CSS 变量，按主题取等价色值（见 lib/chartTheme）
  const palette = useMemo(() => chartPalette(themeMode), [themeMode]);

  const load = async (nextOffset: number, options: { silent?: boolean } = {}) => {
    if (options.silent) setPaging(true);
    else setLoading(true);
    try {
      // 列表按契约分页（limit=20&offset=N）；汇总单独取服务端预算执行（全量口径），
      // 否则「实际支出 / 分类饼图 / 预算对比」会被截断成当前页合计。
      const [page, execution] = await Promise.all([
        getExpensesPage(tripId, { limit: DEFAULT_PAGE_SIZE, offset: nextOffset }),
        getBudgetExecution(tripId).catch(() => null),
      ]);
      setItems(page.items);
      setOffset(nextOffset);
      setMeta({
        total: page.pagination?.total ?? page.items.length,
        hasMore: page.pagination?.hasMore ?? false,
        paged: page.pagination != null,
        limit: page.pagination?.limit ?? DEFAULT_PAGE_SIZE,
      });
      const categories = Array.isArray(execution?.categories) ? execution!.categories : [];
      setSummary(
        execution
          ? {
              total: Number(execution.actual ?? 0),
              byCategory: Object.fromEntries(
                categories.map((c) => [c.category, Number(c.actual ?? 0)]),
              ),
            }
          : summarize(page.items),
      );
    } catch (e: any) {
      message.error(e.message);
    } finally {
      setLoading(false);
      setPaging(false);
    }
  };

  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect -- 行程切换即重拉预算明细：既有「依赖变化即取数」写法，收敛到数据层 hook 属另开卡的重构
    load(0);
    // eslint-disable-next-line react-hooks/exhaustive-deps -- 只按 tripId 重拉：load 每次渲染都会重建，进依赖数组会变成「每次渲染都请求」
  }, [tripId]);

  const { total, byCategory } = summary;

  // 分类饼图
  useEffect(() => {
    if (!chartElRef.current) return;
    const chart = echarts.init(chartElRef.current);
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
    const data = Object.entries(byCategory).map(([name, value]) => ({
      name,
      value: Number(value.toFixed(2)),
    }));
    if (data.length === 0) {
      chart.clear();
      return;
    }
    chart.setOption({
      backgroundColor: 'transparent',
      tooltip: {
        trigger: 'item',
        formatter: '{b}: ¥{c} ({d}%)',
        backgroundColor: 'rgba(17, 26, 51, 0.95)',
        borderColor: 'rgba(255, 255, 255, 0.2)',
        textStyle: { color: TOOLTIP_TEXT_ON_DARK },
      },
      series: [
        {
          type: 'pie',
          radius: ['42%', '72%'],
          center: ['50%', '50%'],
          data,
          color: data.map((d) => CATEGORY_COLORS[d.name] ?? '#cbd5e1'),
          label: { color: palette.textDim, formatter: '{b} ¥{c}' },
          itemStyle: { borderColor: palette.pieBorder, borderWidth: 2 },
        },
      ],
    });
    // 主题切换时必须用新色板重画（canvas 不跟随 CSS 变量）
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [summary, palette]);

  const over = budget != null && total > budget;
  const percent = budget ? Math.min((total / budget) * 100, 100) : 0;
  const remaining = budget != null ? budget - total : null;

  const onFinish = async (v: any) => {
    setSubmitting(true);
    try {
      await addExpense(tripId, {
        category: v.category,
        amount: v.amount,
        note: v.note,
        spentAt: (v.spentAt as Dayjs | undefined)?.format('YYYY-MM-DD'),
        paymentMethod: v.paymentMethod,
      });
      message.success('已记账 ✓');
      form.setFieldValue('amount', undefined);
      form.setFieldValue('note', undefined);
      // 新记录在列表最前，回到第一页（筛选/其它页面状态不受影响）
      await load(0, { silent: true });
    } catch (e: any) {
      message.error(e.message);
    } finally {
      setSubmitting(false);
    }
  };

  const remove = async (id: number) => {
    try {
      await deleteExpense(id);
      message.success('已删除');
      // 删掉当前页最后一条时回退一页，避免停在空页
      const nextOffset =
        items.length === 1 && offset > 0 ? Math.max(offset - meta.limit, 0) : offset;
      await load(nextOffset, { silent: true });
    } catch (e: any) {
      message.error(e.message);
    }
  };

  const onPrev = () => {
    if (paging || offset <= 0) return;
    load(Math.max(offset - meta.limit, 0), { silent: true });
  };

  const onNext = () => {
    if (paging || !meta.hasMore) return;
    load(offset + meta.limit, { silent: true });
  };

  return (
    <div>
      {/* 预算对比 */}
      <div style={{ marginBottom: 14 }}>
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'baseline' }}>
          <span style={{ color: 'var(--ink-2)', fontSize: 12.5 }}>实际支出</span>
          <span
            style={{
              fontSize: 22,
              fontWeight: 800,
              color: over ? 'var(--danger)' : 'var(--primary)',
            }}
          >
            ¥{total.toFixed(0)}
            {budget != null && (
              <span style={{ fontSize: 12.5, color: 'var(--ink-3)', fontWeight: 400 }}>
                {' '}
                / ¥{budget}
              </span>
            )}
          </span>
        </div>
        {budget != null && (
          <Progress
            percent={Number(percent.toFixed(1))}
            showInfo={false}
            strokeColor={
              over ? { from: '#f87171', to: '#dc2626' } : { from: '#38bdf8', to: '#818cf8' }
            }
            trailColor="rgba(255,255,255,0.08)"
            size={['100%', 8]}
            style={{ marginTop: 6 }}
          />
        )}
        <div
          style={{
            fontSize: 11.5,
            color: 'var(--ink-3)',
            marginTop: 4,
            display: 'flex',
            justifyContent: 'space-between',
          }}
        >
          <span>行程预估 ¥{Number(planned ?? 0).toFixed(0)}</span>
          {remaining != null && (
            <span>
              {over ? `已超支 ¥${(-remaining).toFixed(0)}` : `剩余 ¥${remaining.toFixed(0)}`}
            </span>
          )}
        </div>
      </div>

      {/* 分类饼图 */}
      <div ref={chartElRef} style={{ width: '100%', height: 165, marginBottom: 8 }} />
      {items.length === 0 && (
        <Empty
          image={Empty.PRESENTED_IMAGE_SIMPLE}
          description="还没有记账，记录第一笔吧"
          style={{ marginBottom: 10 }}
        />
      )}

      {/* 记一笔 */}
      <Form
        form={form}
        layout="vertical"
        onFinish={onFinish}
        initialValues={{ category: '餐饮', spentAt: dayjs(), paymentMethod: '微信支付' }}
      >
        <div style={{ display: 'flex', gap: 8 }}>
          <Form.Item
            name="category"
            style={{ flex: 1, marginBottom: 8 }}
            rules={[{ required: true }]}
          >
            <Select options={EXPENSE_CATEGORIES.map((c) => ({ value: c, label: c }))} />
          </Form.Item>
          <Form.Item
            name="amount"
            style={{ flex: 1, marginBottom: 8 }}
            rules={[{ required: true, message: '金额' }]}
          >
            <InputNumber min={0.01} precision={2} placeholder="金额 ¥" style={{ width: '100%' }} />
          </Form.Item>
        </div>
        <div style={{ display: 'flex', gap: 8 }}>
          <Form.Item name="spentAt" style={{ flex: 1, marginBottom: 8 }}>
            <DatePicker style={{ width: '100%' }} allowClear={false} />
          </Form.Item>
          <Form.Item name="paymentMethod" style={{ flex: 1, marginBottom: 8 }}>
            <Select
              options={PAYMENT_METHODS.map((p) => ({ value: p, label: p }))}
              optionRender={(o) => (
                <span style={{ color: PAYMENT_COLORS[o.value as string] ?? undefined }}>
                  {o.label}
                </span>
              )}
            />
          </Form.Item>
        </div>
        <Form.Item name="note" style={{ marginBottom: 8 }}>
          <Input placeholder="备注（可选）" maxLength={100} />
        </Form.Item>
        <Button type="primary" block icon={<PlusOutlined />} htmlType="submit" loading={submitting}>
          记一笔
        </Button>
      </Form>

      {/* 流水 */}
      <List
        style={{ marginTop: 14 }}
        loading={loading}
        dataSource={items}
        locale={{ emptyText: '' }}
        renderItem={(item) => (
          <List.Item
            style={{ padding: '8px 2px', borderBottom: '1px solid var(--line)' }}
            actions={[
              <Popconfirm key="del" title="删除这条记录？" onConfirm={() => remove(item.id)}>
                <Button type="text" size="small" danger icon={<DeleteOutlined />} />
              </Popconfirm>,
            ]}
          >
            <div style={{ display: 'flex', gap: 10, alignItems: 'center', width: '100%' }}>
              <span
                style={{
                  fontSize: 11.5,
                  padding: '2px 9px',
                  borderRadius: 999,
                  background: `${CATEGORY_COLORS[item.category] ?? '#cbd5e1'}22`,
                  color: CATEGORY_COLORS[item.category] ?? '#cbd5e1',
                  border: `1px solid ${CATEGORY_COLORS[item.category] ?? '#cbd5e1'}44`,
                  flexShrink: 0,
                }}
              >
                {item.category}
              </span>
              <div style={{ flex: 1, minWidth: 0 }}>
                <div
                  style={{
                    fontSize: 13,
                    overflow: 'hidden',
                    textOverflow: 'ellipsis',
                    whiteSpace: 'nowrap',
                  }}
                >
                  {item.note || item.category}
                </div>
                <div style={{ fontSize: 11, color: 'var(--ink-3)' }}>
                  {item.spentAt}
                  {item.paymentMethod ? ` · ${item.paymentMethod}` : ''}
                </div>
              </div>
              <span style={{ fontWeight: 700, color: 'var(--accent)', flexShrink: 0 }}>
                ¥{item.amount}
              </span>
            </div>
          </List.Item>
        )}
      />

      {/* 翻页：共 N 条 · 上一页/下一页；后端没返回 pagination 时降级为单页 */}
      {items.length > 0 && (
        <PaginationBar
          total={meta.total}
          offset={offset}
          limit={meta.limit}
          hasMore={meta.hasMore}
          paged={meta.paged}
          loading={paging}
          onPrev={onPrev}
          onNext={onNext}
        />
      )}
    </div>
  );
}
