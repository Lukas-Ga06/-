/** 日程条目（并入旅行域；接口响应类型见 api/assistant.ts） */
export interface ScheduleItem {
  id: number;
  title: string;
  date: string;
  time?: string | null;
  note?: string | null;
  done: boolean;
}
