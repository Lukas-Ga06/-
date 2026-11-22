export interface Memo {
  id: number;
  userId: number;
  content: string;
  done: boolean;
  createdAt?: string | null;
}
