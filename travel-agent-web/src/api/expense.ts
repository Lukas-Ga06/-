import { request, getPage, type PageResult } from './client';
import type { AddExpensePayload, Expense, ExpenseOverview } from '../types/expense';

export function getExpenses(tripId: number): Promise<Expense[]> {
  return request<Expense[]>({ url: `/trips/${tripId}/expenses` });
}

/**
 * 账单列表（分页）：GET /api/trips/{id}/expenses?limit=20&offset=N
 * 响应 data 仍是数组；pagination 缺失时 pagination 为 null（按单页降级）。
 */
export function getExpensesPage(
  tripId: number,
  page: { limit?: number; offset?: number } = {},
): Promise<PageResult<Expense>> {
  return getPage<Expense>(`/trips/${tripId}/expenses`, {
    limit: page.limit,
    offset: page.offset,
  });
}

export function addExpense(tripId: number, payload: AddExpensePayload): Promise<Expense> {
  return request<Expense>({ url: `/trips/${tripId}/expenses`, method: 'post', data: payload });
}

export function deleteExpense(id: number): Promise<void> {
  return request<void>({ url: `/expenses/${id}`, method: 'delete' });
}

export function getExpenseOverview(): Promise<ExpenseOverview> {
  return request<ExpenseOverview>({ url: '/expenses/overview' });
}
