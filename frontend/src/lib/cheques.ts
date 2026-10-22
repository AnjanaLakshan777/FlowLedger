import type { BankTransaction, Cheque, ChequeCategory } from "@/data/types"
import { parseDate } from "./format"

export const CHEQUE_CATEGORIES: ChequeCategory[] = ["Supplier Payment", "Salary", "Tax", "Other Expenses"]
/** The category that must say what the payment was. */
export const OTHER_CHEQUE_CATEGORY: ChequeCategory = "Other Expenses"

/** "Other Expenses - Audit fee" for an Other Expenses cheque with a description; otherwise the category. */
export const chequeCategoryText = (c: Pick<Cheque, "category" | "other_description">) =>
  c.category === OTHER_CHEQUE_CATEGORY && c.other_description ? `${c.category} - ${c.other_description}` : c.category

/** What the Finance Controller enters for a future cheque payment. */
export interface ChequeInput {
  category: string; other_description: string; payee: string; amount: number | null; cheque_no: string; cheque_date: string; expected_clear_date: string; notes: string
}
export type ChequeErrors = Partial<Record<"category" | "other_description" | "payee" | "amount" | "cheque_no" | "cheque_date" | "expected_clear_date", string>>

const isIsoDate = (s: string) => /^\d{4}-\d{2}-\d{2}$/.test(s) && !Number.isNaN(parseDate(s).getTime()) && parseDate(s).toISOString().slice(0, 10) === s

/** A scheduled cheque whose expected clearing date is after `today`: a future outflow, and the only kind that can be edited. */
export const isUpcoming = (c: Cheque, today: string) => c.status === "Scheduled" && c.expected_clear_date > today
/**
 * A scheduled cheque on or past its expected clearing date. It is NOT assumed paid: it is shown as cleared only when an imported
 * bank transaction confirms it (see `clearings`); until then it is shown as past its clearing date.
 */
export const isPastClearingDate = (c: Cheque, today: string) => c.status === "Scheduled" && c.expected_clear_date <= today
/** A cheque payment the bank confirmed: the imported CHEQUE_PAYMENT debit with the cheque's number. */
export interface Clearing { date: string; amount: number }

/** Bank statements may print cheque numbers with leading zeros. */
const chequeKey = (no: string) => no.replace(/^0+/, "") || "0"

/** Cleared cheques by number, from imported bank transactions classified as cheque payments. */
export function clearings(transactions: BankTransaction[]): Map<string, Clearing> {
  const map = new Map<string, Clearing>()
  for (const t of transactions) {
    if (t.category !== "CHEQUE_PAYMENT" || !t.cheque_no || t.amount >= 0) continue
    const key = chequeKey(t.cheque_no)
    if (!map.has(key) || t.txn_date < map.get(key)!.date) map.set(key, { date: t.txn_date, amount: -t.amount })
  }
  return map
}

export const clearingOf = (c: Cheque, cleared: Map<string, Clearing>) => (c.status === "Scheduled" ? cleared.get(chequeKey(c.cheque_no)) : undefined)

/** Status shown for a cheque: Cleared once the bank's imported transactions show it paid. */
export const paymentState = (c: Cheque, today: string, cleared?: Map<string, Clearing>) =>
  c.status !== "Scheduled" ? c.status : cleared && clearingOf(c, cleared) ? "Cleared" : isPastClearingDate(c, today) ? "Past clearing date" : "Scheduled"

/**
 * Validates a cheque before saving. `originalNo` is the cheque being edited, so it doesn't count as its own duplicate.
 * The expected clearing date must be after `today`, as the payment service requires.
 */
export function validateCheque(f: ChequeInput, existing: Cheque[], today: string, originalNo?: string): ChequeErrors {
  const e: ChequeErrors = {}
  if (!CHEQUE_CATEGORIES.includes(f.category as ChequeCategory)) e.category = "Choose a payment category."
  else if (f.category === OTHER_CHEQUE_CATEGORY && !f.other_description.trim()) e.other_description = "Say what this payment is, for example Audit fee."
  else if (f.category === OTHER_CHEQUE_CATEGORY && f.other_description.trim().length > 120) e.other_description = "Keep it to 120 characters or fewer."
  if (!f.payee.trim()) e.payee = "Enter the payee."
  if (f.amount == null || Number.isNaN(f.amount) || f.amount <= 0) e.amount = "Enter an amount greater than 0."
  if (!/^\d{6}$/.test(f.cheque_no)) e.cheque_no = "Enter the 6-digit cheque number."
  else if (existing.some((c) => c.cheque_no === f.cheque_no && c.cheque_no !== originalNo)) e.cheque_no = "This cheque number is already recorded."
  if (!isIsoDate(f.cheque_date)) e.cheque_date = "Enter a valid cheque issue date."
  if (!isIsoDate(f.expected_clear_date)) e.expected_clear_date = "Enter the expected clearing date."
  else if (isIsoDate(f.cheque_date) && f.expected_clear_date < f.cheque_date) e.expected_clear_date = "Clearing can't be before the cheque issue date."
  else if (f.expected_clear_date <= today) e.expected_clear_date = "Choose an expected clearing date after today."
  return e
}

/** Builds the stored cheque (a scheduled future payment). */
export const toCheque = (f: ChequeInput, base?: Cheque): Cheque => ({
  cheque_no: f.cheque_no, payee: f.payee.trim(), category: f.category as ChequeCategory,
  other_description: f.category === OTHER_CHEQUE_CATEGORY ? f.other_description.trim() : "", amount: f.amount!, cheque_date: f.cheque_date,
  expected_clear_date: f.expected_clear_date, status: base?.status ?? "Scheduled", notes: f.notes,
})

/** Adds a cheque, or replaces the one being edited (matched by its original cheque number) — never a second copy. */
export function upsertCheque(list: Cheque[], cheque: Cheque, originalNo?: string): Cheque[] {
  const key = originalNo ?? cheque.cheque_no
  return list.some((c) => c.cheque_no === key) ? list.map((c) => (c.cheque_no === key ? cheque : c)) : [...list, cheque]
}
