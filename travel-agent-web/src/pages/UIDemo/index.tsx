import { useState } from 'react';
import { Button, Input } from 'antd';
import { PlusOutlined } from '@ant-design/icons';
import AppShell from '../../components/AppShell';
import CountUp from '../../components/CountUp';
import Skeleton from '../../components/Skeleton';
import SwipeRow from '../../components/SwipeRow';
import DraggableList from '../../components/DraggableList';
import Collapse from '../../components/Collapse';
import { toast } from '../../components/Toast';
import { useIntersection } from '../../hooks/useIntersection';

interface DemoItem {
  id: string;
  label: string;
  value: number;
}

const SAMPLE_ITEMS: DemoItem[] = [
  { id: '1', label: '上海外滩', value: 1280 },
  { id: '2', label: '杭州西湖', value: 860 },
  { id: '3', label: '北京故宫', value: 2400 },
  { id: '4', label: '成都太古里', value: 520 },
];

/**
 * 复杂 UI 组件与交互效果演示页
 * 展示 CountUp、Skeleton、SwipeRow、DraggableList、Collapse、Toast
 */
export default function UIDemo() {
  const [count, setCount] = useState(42);
  const [money, setMoney] = useState(9999);
  const [loading, setLoading] = useState(false);
  const [items, setItems] = useState<DemoItem[]>(SAMPLE_ITEMS);
  const [listItems, setListItems] = useState<DemoItem[]>(SAMPLE_ITEMS);
  const [collapseOpen, setCollapseOpen] = useState(false);
  const [visibleRef, inView] = useIntersection<HTMLDivElement>({ threshold: 0.2 });

  const showToast = (type: 'success' | 'warning' | 'error' | 'info' | 'ai') => {
    const messages = {
      success: { title: '操作成功', content: '数据已保存' },
      warning: { title: '注意', content: '预算超出阈值' },
      error: { title: '出错', content: '网络连接失败' },
      info: { title: '提示', content: '点击左滑查看更多操作' },
      ai: { title: 'AI 建议', content: '建议把第二天的户外行程移到上午' },
    };
    toast[type](messages[type].content, messages[type].title);
  };

  return (
    <AppShell active="home">
      <div className="dash-wrap">
        <div className="dash-greeting">
          <div>
            <h1>✨ UI 组件演示</h1>
            <div className="dg-date">CountUp · Skeleton · SwipeRow · DraggableList · Collapse · Toast · Intersection</div>
          </div>
        </div>

        {/* === CountUp === */}
        <section className="dash-card fade-up">
          <div className="dc-head">
            <b>CountUp · 数字滚动动画</b>
            <Button size="small" onClick={() => { setCount(Math.floor(Math.random() * 500)); setMoney(Math.floor(Math.random() * 100000)); }}>
              随机刷新
            </Button>
          </div>
          <div className="stat-row" style={{ marginTop: 12 }}>
            <div className="stat-card">
              <div className="sc-icon blue">📊</div>
              <div className="sc-num"><CountUp value={count} duration={800} /></div>
              <div className="sc-label">整数</div>
            </div>
            <div className="stat-card">
              <div className="sc-icon green">💰</div>
              <div className="sc-num"><CountUp value={money} prefix="¥" thousandSeparator duration={1000} /></div>
              <div className="sc-label">金额（千分位）</div>
            </div>
            <div className="stat-card">
              <div className="sc-icon orange">📈</div>
              <div className="sc-num"><CountUp value={Math.PI * 100} decimals={2} suffix="%" duration={700} /></div>
              <div className="sc-label">小数百分比</div>
            </div>
            <div className="stat-card">
              <div className="sc-icon purple">🎯</div>
              <div className="sc-num"><CountUp value={3650} prefix="¥" suffix="k" decimals={1} duration={600} /></div>
              <div className="sc-label">带前后缀</div>
            </div>
          </div>
        </section>

        {/* === Skeleton === */}
        <section className="dash-card fade-up">
          <div className="dc-head">
            <b>Skeleton · 骨架屏</b>
            <Button size="small" onClick={() => { setLoading(true); setTimeout(() => setLoading(false), 2000); }}>
              {loading ? '加载中…' : '加载'}
            </Button>
          </div>
          {loading ? (
            <>
              <div className="stat-row" style={{ marginTop: 12 }}>
                <Skeleton.StatRow />
              </div>
              <div style={{ marginTop: 16 }}>
                {[0, 1].map((i) => (
                  <div key={i} style={{ marginBottom: 8 }}>
                    <Skeleton.ListItem />
                  </div>
                ))}
              </div>
            </>
          ) : (
            <div style={{ padding: '20px 0', color: 'var(--text-2)', fontSize: 14 }}>
              <p>点击「加载」可以看到骨架屏 2 秒动画效果。</p>
              <p>骨架屏支持：文本行、段落、圆形头像、卡片、列表行、统计行、聊天气泡。</p>
            </div>
          )}
        </section>

        {/* === SwipeRow === */}
        <section className="dash-card fade-up">
          <div className="dc-head">
            <b>SwipeRow · 滑动操作</b>
          </div>
          <div style={{ display: 'flex', flexDirection: 'column', gap: 8, marginTop: 12 }}>
            {items.map((item) => (
              <SwipeRow
                key={item.id}
                rightActions={[
                  {
                    label: '完成',
                    bg: 'var(--ok)',
                    onClick: () => toast.success(`已标记完成：${item.label}`),
                  },
                  {
                    label: '删除',
                    bg: 'var(--danger)',
                    onClick: () => {
                      setItems((prev) => prev.filter((i) => i.id !== item.id));
                      toast.info(`已删除：${item.label}`);
                    },
                  },
                ]}
                leftActions={[
                  {
                    label: '置顶',
                    bg: 'var(--accent)',
                    onClick: () => toast.info(`已置顶：${item.label}`),
                  },
                ]}
                threshold={70}
                maxSlide={180}
              >
                <div style={{ display: 'flex', alignItems: 'center', gap: 12, padding: '12px 16px' }}>
                  <span style={{ fontSize: 22 }}>🧭</span>
                  <div style={{ flex: 1 }}>
                    <div style={{ fontWeight: 600, fontSize: 14 }}>{item.label}</div>
                    <div style={{ fontSize: 12, color: 'var(--text-3)' }}>¥{item.value}</div>
                  </div>
                  <span style={{ fontSize: 12, color: 'var(--text-3)' }}>← 左右滑动 →</span>
                </div>
              </SwipeRow>
            ))}
          </div>
        </section>

        {/* === DraggableList === */}
        <section className="dash-card fade-up">
          <div className="dc-head">
            <b>DraggableList · 拖拽排序</b>
          </div>
          <div style={{ marginTop: 12 }}>
            <DraggableList
              items={listItems}
              itemKey={(item) => item.id}
              onReorder={(next) => {
                setListItems(next);
                toast.success('排序已更新');
              }}
              renderItem={(item, index) => (
                <div style={{ display: 'flex', alignItems: 'center', gap: 12, padding: '10px 16px' }}>
                  <span style={{ fontSize: 16 }}>#{index + 1}</span>
                  <span style={{ flex: 1, fontWeight: 500 }}>{item.label}</span>
                  <span style={{ fontWeight: 700, color: 'var(--accent)' }}>¥{item.value}</span>
                </div>
              )}
            />
          </div>
        </section>

        {/* === Collapse === */}
        <section className="dash-card fade-up">
          <div className="dc-head">
            <b>Collapse · 折叠展开</b>
            <Button size="small" onClick={() => setCollapseOpen((v) => !v)}>
              {collapseOpen ? '收起' : '展开'}
            </Button>
          </div>
          <Collapse open={collapseOpen} duration={280}>
            <div style={{ padding: '12px 0', color: 'var(--text-2)', lineHeight: 1.7 }}>
              <p>这里是折叠内容。展开和收起时高度从 0 → auto 平滑过渡。</p>
              <p>原理：展开时先用 scrollHeight 测出真实高度，用 px 做动画，结束后设为 auto。</p>
              <p>这样内容变化（如展开后添加更多行）也不会出现高度不匹配。</p>
            </div>
          </Collapse>
        </section>

        {/* === Toast === */}
        <section className="dash-card fade-up">
          <div className="dc-head">
            <b>Toast · 通知提示</b>
          </div>
          <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap', marginTop: 12 }}>
            <Button onClick={() => showToast('success')}>成功</Button>
            <Button onClick={() => showToast('warning')}>警告</Button>
            <Button danger onClick={() => showToast('error')}>错误</Button>
            <Button onClick={() => showToast('info')}>信息</Button>
            <Button type="primary" onClick={() => showToast('ai')}>AI 消息</Button>
          </div>
        </section>

        {/* === Intersection === */}
        <div style={{ height: 300, display: 'flex', alignItems: 'flex-end', justifyContent: 'center', marginTop: 20 }}>
          <span style={{ fontSize: 12, color: 'var(--text-3)' }}>↓ 向下滚动到视口触发入场动画</span>
        </div>
        <div ref={visibleRef} className={inView ? 'in-view' : ''} style={{ opacity: inView ? undefined : 0 }}>
          <section className="dash-card" style={{ minHeight: 120 }}>
            <div className="dc-head"><b>useIntersection · 视口触发</b></div>
            <div style={{ padding: 12, color: 'var(--text-2)', fontSize: 14 }}>
              {inView ? '✅ 元素已进入视口，触发了 in-view 动画' : '⏳ 等待进入视口…'}
            </div>
          </section>
        </div>
      </div>
    </AppShell>
  );
}
