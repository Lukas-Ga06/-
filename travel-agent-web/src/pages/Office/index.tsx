import { useEffect, useState } from 'react';
import { App, Button, Empty, Input, Modal, Select, Tabs } from 'antd';
import AppShell from '../../components/AppShell';
import { useTripStore } from '../../store/tripStore';
import { PAYMENT_METHODS } from '../../types/expense';
import {
  classifyMail, generateMinutes, generateReport, extractInvoice,
  listMail, listMinutes, listReports, listInvoices, invoiceToExpense,
  type MailItem, type MeetingMinutes, type DailyReport, type Invoice,
} from '../../api/assistant';

const MAIL_TAG: Record<string, string> = {
  重要: 'rgba(255, 59, 48, 0.08)', 会议: 'rgba(0, 122, 255, 0.08)', 账单: 'rgba(255, 149, 0, 0.1)',
  通知: 'rgba(90, 200, 250, 0.12)', 垃圾: 'rgba(142, 142, 147, 0.12)', 其他: 'rgba(142, 142, 147, 0.1)',
};
const MAIL_COLOR: Record<string, string> = {
 重要: '#dc2626', 会议: '#1f6feb', 账单: '#d97706', 通知: '#2f9fb8', 垃圾: '#8b93a1', 其他: '#5b6472',
};

function MdView({ text }: { text: string }) {
  return (
    <pre style={{ whiteSpace: 'pre-wrap', fontFamily: 'inherit', fontSize: 13, lineHeight: 1.75, margin: 0, color: 'var(--ink)' }}>
      {text}
    </pre>
  );
}

/** 办公自动化模块：邮件分类 / 会议纪要 / 日报生成 / 发票整理 / 日程 */
export default function Office() {
  const { message } = App.useApp();

  // 邮件
  const [mailText, setMailText] = useState('');
  const [mails, setMails] = useState<MailItem[]>([]);
  const [mailBusy, setMailBusy] = useState(false);
  const loadMail = async () => { try { setMails(await listMail()); } catch (e: any) { message.error(e.message); } };
  const doClassify = async () => {
    if (!mailText.trim()) { message.warning('粘贴邮件内容'); return; }
    setMailBusy(true);
    try {
      await classifyMail({ text: mailText });
      message.success('分类完成');
      setMailText('');
      loadMail();
    } catch (e: any) { message.error(e.message); } finally { setMailBusy(false); }
  };

  // 纪要
  const [mTitle, setMTitle] = useState('');
  const [mText, setMText] = useState('');
  const [minutes, setMinutes] = useState<MeetingMinutes[]>([]);
  const [mBusy, setMBusy] = useState(false);
  const loadMinutes = async () => { try { setMinutes(await listMinutes()); } catch (e: any) { message.error(e.message); } };
  const doMinutes = async () => {
    if (!mText.trim()) { message.warning('粘贴会议记录'); return; }
    setMBusy(true);
    try {
      await generateMinutes({ title: mTitle || '未命名会议', rawText: mText });
      message.success('纪要已生成');
      setMTitle(''); setMText('');
      loadMinutes();
    } catch (e: any) { message.error(e.message); } finally { setMBusy(false); }
  };

  // 日报
  const [reports, setReports] = useState<DailyReport[]>([]);
  const [rBusy, setRBusy] = useState(false);
  const loadReports = async () => { try { setReports(await listReports()); } catch (e: any) { message.error(e.message); } };
  const doReport = async () => {
    setRBusy(true);
    try {
      await generateReport();
      message.success('日报已生成');
      loadReports();
    } catch (e: any) { message.error(e.message); } finally { setRBusy(false); }
  };

  // 发票
  const [invText, setInvText] = useState('');
  const [invoices, setInvoices] = useState<Invoice[]>([]);
  const [invBusy, setInvBusy] = useState(false);
  const loadInvoices = async () => { try { setInvoices(await listInvoices()); } catch (e: any) { message.error(e.message); } };
  const [expenseInvoice, setExpenseInvoice] = useState<Invoice | null>(null);
  const [expenseTripId, setExpenseTripId] = useState<number | null>(null);
  const [expensePayment, setExpensePayment] = useState('现金');
  const [expenseBusy, setExpenseBusy] = useState(false);
  const trips = useTripStore((s) => s.trips);
  const loadTrips = useTripStore((s) => s.loadTrips);
  const doInvoice = async () => {
    if (!invText.trim()) { message.warning('粘贴发票文本'); return; }
    setInvBusy(true);
    try {
      await extractInvoice(invText);
      message.success('发票已提取入库');
      setInvText('');
      loadInvoices();
    } catch (e: any) { message.error(e.message); } finally { setInvBusy(false); }
  };

  const submitInvoiceExpense = async () => {
    if (!expenseInvoice || !expenseTripId) return;
    setExpenseBusy(true);
    try {
      await invoiceToExpense(expenseInvoice.id, { tripId: expenseTripId, paymentMethod: expensePayment });
      message.success('已记入行程支出');
      setExpenseInvoice(null);
      setExpenseTripId(null);
      loadInvoices();
    } catch (e: any) {
      message.error(e.message);
    } finally {
      setExpenseBusy(false);
    }
  };

  useEffect(() => { loadMail(); loadMinutes(); loadReports(); loadInvoices(); loadTrips().catch(() => undefined); }, []);

  return (
    <AppShell active="office">
      <div className="dash-wrap">
        <div className="dash-greeting">
          <div>
            <h1>🏢 办公自动化</h1>
            <div className="dg-date">邮件分类 · 会议纪要 · 日报生成 · 发票整理 · 日程管理（生成能力由大模型驱动）</div>
          </div>
        </div>

        <div className="inset-list fade-up" style={{ padding: '6px 18px 18px' }}>
          <Tabs
            items={[
              {
                key: 'mail', label: '邮件分类',
                children: (
                  <>
                    <Input.TextArea rows={4} placeholder="粘贴邮件内容（发件人/主题/正文）" value={mailText} onChange={(e) => setMailText(e.target.value)} />
                    <Button type="primary" style={{ marginTop: 10 }} loading={mailBusy} onClick={doClassify}>分类并保存</Button>
                    <div style={{ marginTop: 14 }}>
                      {mails.length === 0 ? <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="还没有分类记录" /> :
                        mails.map((m) => (
                          <div key={m.id} className="memo-row">
                            <span style={{ fontSize: 11.5, padding: '2px 9px', borderRadius: 999, background: MAIL_TAG[m.category] ?? MAIL_TAG['其他'], color: MAIL_COLOR[m.category] ?? 'var(--ink)', fontWeight: 600, flexShrink: 0 }}>{m.category}</span>
                            <div style={{ flex: 1, minWidth: 0 }}>
                              <div style={{ fontSize: 13.5, fontWeight: 600 }}>{m.subject || '(无主题)'}</div>
                              <div style={{ fontSize: 12, color: 'var(--ink-2)' }}>{m.summary}</div>
                              {m.suggestedReply && <div style={{ fontSize: 12, color: 'var(--blue)', marginTop: 2 }}>建议回复：{m.suggestedReply}</div>}
                            </div>
                          </div>
                        ))}
                    </div>
                  </>
                ),
              },
              {
                key: 'minutes', label: '会议纪要',
                children: (
                  <>
                    <Input placeholder="会议标题（可选）" value={mTitle} onChange={(e) => setMTitle(e.target.value)} style={{ marginBottom: 8 }} />
                    <Input.TextArea rows={5} placeholder="粘贴会议记录/聊天记录/语音转写文本…" value={mText} onChange={(e) => setMText(e.target.value)} />
                    <Button type="primary" style={{ marginTop: 10 }} loading={mBusy} onClick={doMinutes}>生成纪要</Button>
                    <div style={{ marginTop: 14 }}>
                      {minutes.length === 0 ? <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="还没有生成过纪要" /> :
                        minutes.map((m) => (
                          <div key={m.id} style={{ background: 'var(--bg-grouped)', borderRadius: 12, padding: 14, marginBottom: 10 }}>
                            <div style={{ fontWeight: 700, marginBottom: 6 }}>{m.title} <span style={{ fontSize: 11, color: 'var(--ink-3)', fontWeight: 400 }}>{m.createdAt?.slice(0, 16)?.replace('T', ' ')}</span></div>
                            <MdView text={m.content} />
                          </div>
                        ))}
                    </div>
                  </>
                ),
              },
              {
                key: 'report', label: '日报生成',
                children: (
                  <>
                    <div style={{ fontSize: 13, color: 'var(--ink-2)', marginBottom: 10 }}>
                      自动汇总「今天」的记账、备忘、日程真实数据，由大模型写成结构化日报。
                    </div>
                    <Button type="primary" loading={rBusy} onClick={doReport}>生成今日日报</Button>
                    <div style={{ marginTop: 14 }}>
                      {reports.length === 0 ? <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="还没有日报" /> :
                        reports.map((r) => (
                          <div key={r.id} style={{ background: 'var(--bg-grouped)', borderRadius: 12, padding: 14, marginBottom: 10 }}>
                            <div style={{ fontWeight: 700, marginBottom: 6 }}>{r.date} 日报</div>
                            <MdView text={r.content} />
                          </div>
                        ))}
                    </div>
                  </>
                ),
              },
              {
                key: 'invoice', label: '发票整理',
                children: (
                  <>
                    <Input.TextArea rows={4} placeholder="粘贴发票文本（发票抬头/公司/金额/日期等）" value={invText} onChange={(e) => setInvText(e.target.value)} />
                    <Button type="primary" style={{ marginTop: 10 }} loading={invBusy} onClick={doInvoice}>提取并入库</Button>
                    <div style={{ marginTop: 14 }}>
                      {invoices.length === 0 ? <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="还没有发票记录" /> :
                        invoices.map((v) => (
                          <div key={v.id} className="memo-row">
                            <span style={{ fontSize: 11.5, padding: '2px 9px', borderRadius: 999, background: 'rgba(0,122,255,0.08)', color: 'var(--blue)', flexShrink: 0 }}>{v.category}</span>
                            <div style={{ flex: 1, minWidth: 0 }}>
                              <div style={{ fontSize: 13.5, fontWeight: 600 }}>{v.title}{v.vendor ? ` · ${v.vendor}` : ''}</div>
                              <div style={{ fontSize: 12, color: 'var(--ink-3)' }}>{v.invoiceDate}</div>
                            </div>
                            <span style={{ fontWeight: 700 }}>¥{v.amount}</span>
                            {v.expenseId ? (
                              <span className="mini-tag" style={{ flexShrink: 0 }}>已记账</span>
                            ) : (
                              <Button
                                size="small"
                                type="primary"
                                ghost
                                onClick={() => {
                                  setExpenseInvoice(v);
                                  setExpenseTripId(null);
                                }}
                              >
                                记一笔
                              </Button>
                            )}
                          </div>
                        ))}
                    </div>
                  </>
                ),
              },
            ]}
          />
        </div>
      </div>

      <Modal
        title={`发票记账 · ${expenseInvoice?.title ?? ''}`}
        open={!!expenseInvoice}
        onCancel={() => setExpenseInvoice(null)}
        onOk={submitInvoiceExpense}
        okText="记一笔"
        confirmLoading={expenseBusy}
        okButtonProps={{ disabled: !expenseTripId }}
      >
        <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
          <div style={{ fontSize: 13, color: 'var(--text-2)' }}>
            金额 <b>¥{expenseInvoice?.amount}</b> · {expenseInvoice?.category} · {expenseInvoice?.invoiceDate}
          </div>
          <Select
            placeholder="选择要记入的行程"
            value={expenseTripId ?? undefined}
            onChange={setExpenseTripId}
            options={trips.map((t) => ({ value: t.id, label: `${t.name}（${t.startDate}）` }))}
            showSearch
            optionFilterProp="label"
          />
          <Select
            value={expensePayment}
            onChange={setExpensePayment}
            options={PAYMENT_METHODS.map((p) => ({ value: p, label: p }))}
          />
        </div>
      </Modal>
    </AppShell>
  );
}
