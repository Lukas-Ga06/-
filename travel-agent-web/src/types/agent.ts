export interface AgentFocus {
  type: string; // itinerary / map / budget
  tripId?: number | null;
  day?: number | null;
  itemId?: number | null;
  latitude?: number | null;
  longitude?: number | null;
}

export interface DiffChange {
  type: string; // ADD / REMOVE / MODIFY
  itemId?: number | null;
  itemType?: string | null;
  title?: string | null;
  detail?: string | null;
  cost?: number | null;
  dayIndex?: number | null;
  startTime?: string | null;
}

export interface PlanDiffDTO {
  changes: DiffChange[];
  budgetBefore: number | null;
  budgetAfter: number | null;
}

export interface BudgetSummaryDTO {
  total: number;
  previousTotal?: number | null;
  difference?: number | null;
  breakdown?: Record<string, number> | null;
}

export interface ConfirmationDTO {
  planId: string;
  summary: string;
  riskLevel?: string;
}

export interface AgentResult {
  planId?: string | null;
  tripId?: number | null;
  message: string;
  intent?: string | null;
  status: string; // COMPLETED / NEED_CONFIRMATION / FAILED
  focus?: AgentFocus | null;
  diff?: PlanDiffDTO | null;
  budget?: BudgetSummaryDTO | null;
  confirmation?: ConfirmationDTO | null;
  warnings?: string[] | null;
}

export interface ChatMessage {
  id: string;
  role: 'user' | 'assistant';
  content: string;
  time: string;
}

export interface SelectedElement {
  type: string;
  id: number | null;
  title: string | null;
}
