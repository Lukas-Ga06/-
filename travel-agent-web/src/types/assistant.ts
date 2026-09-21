export interface ScheduleItem {
  id: number;
  title: string;
  date: string;
  time?: string | null;
  note?: string | null;
  done: boolean;
}

export interface MailItem {
  id: number;
  sender?: string | null;
  subject?: string | null;
  category: string;
  summary?: string | null;
  suggestedReply?: string | null;
  createdAt?: string | null;
}

export interface MeetingMinutes {
  id: number;
  title: string;
  content: string;
  createdAt?: string | null;
}

export interface DailyReport {
  id: number;
  date: string;
  content: string;
  createdAt?: string | null;
}

export interface Invoice {
  id: number;
  title?: string | null;
  vendor?: string | null;
  category: string;
  amount: number;
  invoiceDate: string;
}

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

export interface LearningArtifact {
  id: number;
  kind: 'FRAMEWORK' | 'PATH' | 'SUMMARY' | string;
  topic: string;
  content: string;
  createdAt?: string | null;
}

export interface KnowledgeHit {
  title: string;
  snippet: string;
  score: number;
}
