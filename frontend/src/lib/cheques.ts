import type { Cheque, ChequeCategory } from "@/data/types"
import { parseDate } from "./format"

export const CHEQUE_CATEGORIES: ChequeCategory[] = ["Supplier Payment", "Salary", "Tax", "Other Expenses"]

/** What the Finance Controller enters for a future cheque payment. */
export interface ChequeInput {
  category: string; payee: string; amount: number | null; cheque_no: string; cheque_date: string; expected_clear_date: string; notes: string
}
export type ChequeErrors = Partial<Record<"category" | "payee" | "amount" | "cheque_no" | "cheque_date" | "expected_clear_date", string>>

const isIsoDate = (s: string) => /^\d{4}-\d{2}-\d{2}$/.test(s) && !Number.isNaN(parseDate(s).getTime()) && parseDate(s).toISOString().slice(0, 10) === s

/**
 * Validates a cheque before saving. `originalNo` is the cheque being edited, so it doesn't count as its own duplicate.
 * The expected clearing date must be after `today`: an earlier date would already be paid and in the Actual Bank Balance.
 */
export function validateCheque(f: ChequeInput, existing: Cheque[], today: string, originalNo?: string): ChequeErrors {
  const e: ChequeErrors = {}
  if (!CHEQUE_CATEGORIES.includes(f.category as ChequeCategory)) e.category = "Choose a payment category."
  if (!f.payee.trim()) e.payee = "Enter the payee."
  if (f.amount == null || Number.isNaN(f.amount) || f.amount <= 0) e.amount = "Enter an amount greater than 0."
  if (!/^\d{6}$/.test(f.cheque_no)) e.cheque_no = "Enter the 6-digit cheque number."
  else if (existing.some((c) => c.cheque_no === f.cheque_no && c.cheque_no !== originalNo)) e.cheque_no = "This cheque number is already recorded."
  if (!isIsoDate(f.cheque_date)) e.cheque_date = "Enter a valid cheque issue date."
  if (!isIsoDate(f.expected_clear_date)) e.expected_clear_date = "Enter the expected clearing date."
  else if (isIsoDate(f.cheque_date) && f.expected_clear_date < f.cheque_date) e.expected_clear_date = "Clearing can't be before the cheque issue date."
  else if (f.expected_clear_date <= today) e.expected_clear_date = "Choose a date after today — earlier payments are already in the Actual Bank Balance."
  return e
}

/** Builds the stored cheque (a scheduled future payment). */
export const toCheque = (f: ChequeInput, base?: Cheque): Cheque => ({
  cheque_no: f.cheque_no, payee: f.payee.trim(), category: f.category as ChequeCategory, amount: f.amount!, cheque_date: f.cheque_date,
  expected_clear_date: f.expected_clear_date, status: base?.status ?? "Scheduled", notes: f.notes,
})

/** Adds a cheque, or replaces the one being edited (matched by its original cheque number) — never a second copy. */
export function upsertCheque(list: Cheque[], cheque: Cheque, originalNo?: string): Cheque[] {
  const key = originalNo ?? cheque.cheque_no
  return list.some((c) => c.cheque_no === key) ? list.map((c) => (c.cheque_no === key ? cheque : c)) : [...list, cheque]
}
