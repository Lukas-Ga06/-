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
  sourceFile?: string | null;
  /** PDF / IMAGE / TEXT */
  sourceType?: string | null;
}

export const extractInvoice = (text: string) =>
  request<Invoice>({ url: '/office/invoice/extract', method: 'post', data: { text } });
export const listInvoices = () => request<Invoice[]>({ url: '/office/invoice' });

/** 上传发票文件（PDF 直接识别；图片需服务端配置 OCR） */
export function uploadInvoice(file: File) {
  const form = new FormData();
  form.append('file', file);
  return request<Invoice>({ url: '/office/invoice/upload', method: 'post', data: form });
}

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
  /**
   * 最近一次真实 LLM 调用是否成功（V2.2 P5）。
   * 旧后端没有该字段：调用方必须按 undefined 处理，不能当成「已验证」。
   */
  llmVerified?: boolean;
  llmModel?: string | null;
  llmHint: string;
  amapConfigured: boolean;
  geoProvider: string;
  geoHint: string;
  timezone: string;
  /** 现行路线口径：amap | estimated（V2.2 FR-05） */
  routeProvider?: string;
  /** 是否配置了路线 Provider（后端 routeConfigured；旧后端缺省时回退 amapConfigured） */
  routeConfigured?: boolean;
  /** 最近一次真实路径调用是否成功（V2.2 P2）；旧后端缺省按 undefined 处理 */
  amapVerified?: boolean;
  /** 最近一次真实路径调用失败原因（含 infocode，不含 Key） */
  routeLastError?: string | null;
  routeHint?: string;
  /** 每日 LLM 配额：仅在配置了上限（>0）时返回 */
  llmQuotaUsed?: number;
  llmQuotaLimit?: number;
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
