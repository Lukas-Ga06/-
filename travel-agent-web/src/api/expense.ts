import { request } from './client';
import type { AddExpensePayload, Expense, ExpenseOverview } from '../types/expense';

export function getExpenses(tripId: number): Promise<Expense[]> {
  return request<Expense[]>({ url: `/trips/${tripId}/expenses` });
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
