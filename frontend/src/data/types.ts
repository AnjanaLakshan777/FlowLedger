// Shapes of the demonstration data. Replace the JSON imports in ./index.ts with API calls when the backend exists.
export type SyncStatus = "Synced" | "Error"
/** The company's single current account (MVP). Card sales pass through a separate card settlement account, which is not tracked here. */
export interface BankAccount {
  bank: string; account_name: string; account_type: "Current"; account_masked: string; branch: string; currency: string
  /** Actual Bank Balance — latest balance reported by the bank, as of `balance_as_of`. */
  balance: number; balance_as_of: string; sync_status: SyncStatus
  /** Last successful sync. */
  last_sync: string; integration: string; error: string
}
/**
 * Set by the backend when it matches bank data to FlowLedger records.
 * Only transactions that actually happened in the bank account. Excluded = duplicate record, never counted.
 */
export type MatchStatus = "Matched" | "Unmatched" | "Review" | "Excluded"
export interface BankTransaction {
  txn_id: string; txn_date: string; value_date: string; description: string; reference: string
  /** Credit is positive, debit is negative. */
  amount: number; txn_type: string; match_status: MatchStatus; matched_record: string; confidence: number | null; note: string; balance_after: number | null
}
export interface DailySale {
  sales_date: string; cash_sales: number; card_sales: number; total: number; entered_at: string; entered_by: string; entry_type: "Standard" | "Backdated"; note: string
}
export type ChequeCategory = "Supplier Payment" | "Salary" | "Tax" | "Other Expenses"
/**
 * MVP: a cheque is a scheduled future payment, assumed to be paid on its expected clearing date.
 * There is no bank-based clearing or bounce detection yet; Cancelled is the only status a user can set.
 * Bounced is a recorded outcome only (no detection or bounce workflow): never forecast and never assumed paid.
 */
export type ChequeStatus = "Scheduled" | "Cancelled" | "Bounced"
export interface Cheque {
  cheque_no: string; payee: string; category: ChequeCategory; amount: number; cheque_date: string
  /** The forecast deducts the amount on this date (never on the cheque date). */
  expected_clear_date: string
  status: ChequeStatus; notes: string
}
/** Predicted sales by sales date (from the forecasting service). */
export interface PredictedSale { date: string; cash_sales: number; card_sales: number }
export interface ForecastDay {
  date: string; week: number; weekday: string; opening: number
  /** Predicted sales made on this date (they reach the current account later). */
  cash_sales: number; card_sales: number
  /** Sales receipts expected to arrive in the current account on this date. */
  receipts: number
  cheque_out: number; cheques: number; closing: number; low: number; high: number; top_outflows: string
  /** Sales money expected in the current account this day (cash deposits, card settlement transfers). */
  arrivals: ForecastArrival[]
  /** Outstanding cheques the forecast places on this day, largest first. */
  payments: ForecastPayment[]
}
export interface ForecastArrival { kind: "Cash" | "Card"; sales_date: string; amount: number; /** true = entered sales not yet in bank data; false = predicted */ actual: boolean }
export interface ForecastPayment { cheque_no: string; payee: string; category: ChequeCategory; amount: number; date: string }
export interface ForecastWeek {
  week: number; week_start: string; week_end: string; opening: number; cash_sales: number; card_sales: number; receipts: number; cheque_out: number
  closing: number; low: number; high: number; lowest_balance: number; lowest_date: string; shortage_days: number
  first_shortage_date: string | null; shortage_amount: number
}
export interface Shortage {
  shortage_id: string; first_date: string; lowest_date: string; lowest_balance: number; shortage_amount: number; days_negative: number; week: number
  week_end_balance: number
  /** Explanation over the short days only: opening_before (cash at the start of first_date) + receipts_in_window − payments_in_window = lowest_balance. */
  opening_before: number; receipts_in_window: number; payments_in_window: number
  /** Outstanding cheques expected to clear in that window, largest first. */
  payments: ForecastPayment[]
}
export interface ForecastVsActual {
  week_start: string; week_end: string; forecast_in: number; actual_in: number; diff_in: number; forecast_out: number; actual_out: number
  diff_out: number; forecast_closing: number; actual_closing: number; diff_closing: number
}
export interface ImportRow { row: number; date: string; cash_sales: number | null; card_payments: number; status: "Valid" | "Error" | "Warning"; issue: string }
export interface ImportRecord {
  import_id: string; file_name: string; uploaded_at: string; uploaded_by: string; period: string; rows: number
  status: "Awaiting approval" | "Approved" | "Rejected"; approved_by: string; approved_at: string
}
export type Role = "CASHIER" | "ACCOUNTANT" | "FINANCE_CONTROLLER" | "SYSTEM_ADMIN"
export interface User { user_id: string; name: string; email: string; role: Role; status: "Active" | "Invited" | "Deactivated"; created_at: string }
/** Audit entry for a user, role or status change (kept in memory in this frontend-only demo). */
export interface AccessChange { id: string; at: string; by: string; action: "Created user" | "Changed role" | "Deactivated user"; target: string; from: string; to: string }
export interface Setting { key: string; section: string; label: string; value: string }
