export interface Expense {
  id: number;
  tripId: number;
  tripName?: string | null;
  category: string;
  amount: number;
  note?: string | null;
  paymentMethod?: string | null;
  spentAt: string; // yyyy-MM-dd
  createdAt?: string | null;
}

export interface AddExpensePayload {
  category: string;
  amount: number;
  note?: string;
  spentAt?: string;
  paymentMethod?: string;
}

export const EXPENSE_CATEGORIES = ['餐饮', '交通', '住宿', '门票', '购物', '其他'] as const;

export const CATEGORY_COLORS: Record<string, string> = {
  餐饮: '#ff9500',
  交通: '#007aff',
  住宿: '#5856d6',
  门票: '#5ac8fa',
  购物: '#ff2d55',
  其他: '#8e8e93',
};

export const PAYMENT_METHODS = ['微信支付', '支付宝', '现金', '银行卡'] as const;

export const PAYMENT_COLORS: Record<string, string> = {
  微信支付: '#34c759',
  支付宝: '#007aff',
  现金: '#8e8e93',
  银行卡: '#af52de',
};

export interface TripSpend {
  tripId: number;
  tripName: string;
  total: number;
}

export interface ExpenseOverview {
  totalSpent: number;
  monthSpent: number;
  count: number;
  byPaymentMethod: Record<string, number>;
  byCategory: Record<string, number>;
  byTrip: TripSpend[];
  recent: Expense[];
}
