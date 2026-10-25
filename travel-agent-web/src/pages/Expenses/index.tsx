import { useEffect, useState } from 'react';
import { Button, Empty } from 'antd';
import { useNavigate } from 'react-router-dom';
import AppShell from '../../components/AppShell';
import CountUp from '../../components/CountUp';
import Skeleton from '../../components/Skeleton';
import { getExpenseOverview } from '../../api/expense';
import { CATEGORY_COLORS, PAYMENT_COLORS, PAYMENT_METHODS, type ExpenseOverview } from '../../types/expense';

/** 记账模块：跨行程支出总览 */
export default function Expenses() {
  const navigate = useNavigate();
  const [overview, setOverview] = useState<ExpenseOverview | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    getExpenseOverview()
      .then(setOverview)
      .catch(() => setOverview(null))
      .finally(() => setLoading(false));
  }, []);

  const payTotal = overview
    ? Object.values(overview.byPaymentMethod).reduce((s, v) => s + v, 0)
    : 0;

  return (
    <AppShell active="expenses">
      <div className="dash-wrap">
        <div className="dash-greeting">
          <div>
            <h1>💰 记账</h1>
            <div className="dg-date">
              跨行程实际支出与支付渠道统计 · 进入行程可在「记账」面板记录明细，也可以对助理说「记一笔午餐 45 元」
            </div>
          </div>
        </div>

        {loading ? (
          <div className="dash-grid">
            <div className="dash-card" style={{ padding: 18 }}>
              <Skeleton.Paragraph lines={4} />
            </div>
            <div className="dash-card" style={{ padding: 18 }}>
              <Skeleton.Paragraph lines={4} />
            </div>
          </div>
        ) : !overview || overview.count === 0 ? (
          <div className="empty-trips">
            <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="还没有支出记录，去行程里记一笔吧" />
          </div>
        ) : (
          <>
            <div className="dash-grid">
              <div className="dash-card stagger-item" style={{ animationDelay: '0s' }}>
                <div className="dc-head">
                  <b>支出概览</b>
                  <span style={{ fontSize: 12, color: 'var(--ink-3)' }}>{overview.count} 条记录</span>
                </div>
                <div className="exp-stats">
                  <div className="exp-stat">
                    <div className="exp-num">
                      <CountUp value={overview.totalSpent} prefix="¥" duration={1000} />
                    </div>
                    <div className="exp-label">累计支出</div>
                  </div>
                  <div className="exp-stat">
                    <div className="exp-num green">
                      <CountUp value={overview.monthSpent} prefix="¥" duration={1000} />
                    </div>
                    <div className="exp-label">本月</div>
                  </div>
                </div>
                <div className="exp-sub">支付渠道分布</div>
                {PAYMENT_METHODS.map((p) => {
                  const amount = overview.byPaymentMethod?.[p] ?? 0;
                  const pct = payTotal > 0 ? (amount / payTotal) * 100 : 0;
                  return (
                    <div className="pay-row" key={p}>
                      <span className="pay-name" style={{ color: PAYMENT_COLORS[p] }}>
                        {p}
                      </span>
                      <span className="pay-track">
                        <span className="pay-fill" style={{ width: `${pct}%`, background: PAYMENT_COLORS[p] }} />
                      </span>
                      <span className="pay-amount">
                        <CountUp value={amount} prefix="¥" duration={600} />
                      </span>
                    </div>
                  );
                })}
              </div>

              <div className="dash-card stagger-item" style={{ animationDelay: '0.06s' }}>
                <div className="dc-head">
                  <b>按行程</b>
                </div>
                {overview.byTrip.map((t) => (
                  <div
                    key={t.tripId}
                    className="recent-expense-row"
                    style={{ padding: '8px 4px', cursor: t.tripId > 0 ? 'pointer' : 'default' }}
                    // tripId = 0 表示行程已被删除的遗留账单分组，没有可跳转的行程页
                    onClick={() => t.tripId > 0 && navigate(`/trip/${t.tripId}`)}
                  >
                    <span
                      style={{
                        width: 30,
                        height: 30,
                        borderRadius: 9,
                        background: 'var(--grad-primary)',
                        color: '#fff',
                        display: 'grid',
                        placeItems: 'center',
                        fontSize: 13,
                        fontWeight: 700,
                        flexShrink: 0,
                      }}
                    >
                      {(t.tripName || '旅')[0]}
                    </span>
                    <span style={{ flex: 1, fontSize: 13, fontWeight: 600 }}>{t.tripName}</span>
                    <span style={{ fontWeight: 700 }}>¥{t.total.toFixed(0)}</span>
                  </div>
                ))}
              </div>
            </div>

            <div className="section-head">
              <h3>最近记录</h3>
            </div>
            <div className="inset-list fade-up">
              {overview.recent.map((r, i) => (
                <div
                  className="trip-row stagger-item"
                  style={{ animationDelay: `${Math.min(i * 0.04, 0.4)}s` }}
                  key={r.id}
                  onClick={() => navigate(`/trip/${r.tripId}`)}
                >
                  <div
                    className="tr-icon"
                    style={{ background: CATEGORY_COLORS[r.category] ?? '#8e8e93', fontSize: 14 }}
                  >
                    {r.category[0]}
                  </div>
                  <div className="tr-main">
                    <div className="tr-title" style={{ fontWeight: 500, fontSize: 13.5 }}>
                      {r.note || r.category}
                    </div>
                    <div className="tr-sub">
                      {r.tripName ?? ''} · {r.spentAt}
                      {r.paymentMethod ? ` · ${r.paymentMethod}` : ''}
                    </div>
                  </div>
                  <div className="tr-right">
                    <span className="tr-budget">
                      <CountUp value={r.amount} prefix="¥" duration={500} />
                    </span>
                    <span className="tr-chevron">›</span>
                  </div>
                </div>
              ))}
            </div>
          </>
        )}
      </div>
    </AppShell>
  );
}
