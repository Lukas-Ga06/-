import { request } from './client';

// ---------- 日程 ----------
export interface ScheduleItem {
  id: number;
  title: string;
  date: string;
  time?: string | null;
  note?: string | null;
  done: boolean;
  /** NONE / DAILY / WEEKLY / MONTHLY */
  recurrence?: string | null;
  repeatUntil?: string | null;
  nextOccurrence?: string | null;
}

export const listSchedule = () => request<ScheduleItem[]>({ url: '/schedule' });
export const addSchedule = (data: {
  title: string;
  date: string;
  time?: string;
  note?: string;
  recurrence?: string;
  repeatUntil?: string;
}) =>
  request<ScheduleItem>({ url: '/schedule', method: 'post', data });
export const toggleSchedule = (id: number) =>
  request<ScheduleItem>({ url: `/schedule/${id}/toggle`, method: 'post' });
export const deleteSchedule = (id: number) =>
  request<void>({ url: `/schedule/${id}`, method: 'delete' });

// ---------- 办公 ----------
export interface MailItem {
  id: number;
  sender?: string | null;
  subject?: string | null;
  category: string;
  summary?: string | null;
  suggestedReply?: string | null;
  createdAt?: string | null;
}

export const classifyMail = (data: { sender?: string; subject?: string; text: string }) =>
  request<MailItem>({ url: '/office/mail/classify', method: 'post', data });
export const listMail = () => request<MailItem[]>({ url: '/office/mail' });

export interface MeetingMinutes {
  id: number;
  title: string;
  content: string;
  createdAt?: string | null;
}

export const generateMinutes = (data: { title: string; rawText: string }) =>
  request<MeetingMinutes>({ url: '/office/minutes/generate', method: 'post', data });
export const listMinutes = () => request<MeetingMinutes[]>({ url: '/office/minutes' });

export interface DailyReport {
  id: number;
  date: string;
  content: string;
  createdAt?: string | null;
}

export const generateReport = () =>
  request<DailyReport>({ url: '/office/report/generate', method: 'post' });
export const listReports = () => request<DailyReport[]>({ url: '/office/report' });

export interface Invoice {
  id: number;
  title?: string | null;
  vendor?: string | null;
  category: string;
  amount: number;
  invoiceDate: string;
  expenseId?: number | null;
  tripId?: number | null;
}

export const extractInvoice = (text: string) =>
  request<Invoice>({ url: '/office/invoice/extract', method: 'post', data: { text } });
export const listInvoices = () => request<Invoice[]>({ url: '/office/invoice' });

/** 发票一键记账：写入所选行程的一笔支出 */
export const invoiceToExpense = (id: number, data: { tripId: number; paymentMethod?: string }) =>
  request<{ id: number; amount: number; category: string }>({
    url: `/office/invoice/${id}/expense`,
    method: 'post',
    data,
  });

// ---------- 学习 ----------
export interface LearningArtifact {
  id: number;
  kind: string;
  topic: string;
  content: string;
  createdAt?: string | null;
}

export const generateLearning = (data: { kind: string; topic: string; text?: string }) =>
  request<LearningArtifact>({ url: '/learning/generate', method: 'post', data });
export const listArtifacts = (kind?: string) =>
  request<LearningArtifact[]>({ url: '/learning/artifacts', params: kind ? { kind } : {} });
export const deleteArtifact = (id: number) =>
  request<void>({ url: `/learning/artifacts/${id}`, method: 'delete' });

export const addKnowledge = (data: { title: string; content: string }) =>
  request<{ documentId: number; chunks: number }>({ url: '/learning/knowledge/add', method: 'post', data });
export const searchKnowledge = (query: string) =>
  request<{ title: string; snippet: string; score: number }[]>({
    url: '/learning/knowledge/search',
    method: 'post',
    data: { query },
  });

// ---------- 健康 ----------
export interface SystemStatus {
  llmConfigured: boolean;
  llmModel?: string | null;
  llmHint: string;
  amapConfigured: boolean;
  geoProvider: string;
  geoHint: string;
  timezone: string;
}

export const systemStatus = () => request<SystemStatus>({ url: '/system/status' });

export interface HealthRecord {
  id?: number;
  date: string;
  steps?: number | null;
  sleepHours?: number | null;
  restingHeartRate?: number | null;
  weight?: number | null;
}

export interface HealthSummary {
  latest: HealthRecord;
  avgSteps: number;
  avgSleep: number;
  days: number;
  advice: string;
}

export const upsertHealth = (data: {
  date: string;
  steps?: number | null;
  sleepHours?: number | null;
  heartRate?: number | null;
  weight?: number | null;
}) => request<HealthRecord>({ url: '/health/record', method: 'post', data });
export const listHealth = () => request<HealthRecord[]>({ url: '/health/records' });
export const healthSummary = () => request<HealthSummary>({ url: '/health/summary' });
export const seedHealthDemo = () =>
  request<{ created: number }>({ url: '/health/seed-demo', method: 'post' });
