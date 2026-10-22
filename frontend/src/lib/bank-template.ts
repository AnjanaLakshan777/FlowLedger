/**
 * FlowLedger's monthly bank transactions template: the bank's own "Transaction details" report, row by row as the bank
 * prints it, with two columns added — Category and Other Description. The Accountant pastes the month's rows after it ends.
 */

export const BANK_TEMPLATE_FILE_NAME = "flowledger_bank_transactions_template.csv"

/** Newest first, as the bank prints it; oldest first works too. */
export const BANK_TEMPLATE_CSV = [
  "Date,Description,Transaction Type,Credit Amount,Debit Amount,Running Balance,Cheque Number,Branch ID,Category,Other Description",
  "9/30/2026,INTEREST WITHHELD,Debit,,1.43,8373.55,000000000,631,OTHER,Withholding tax",
  "9/30/2026,INTEREST PAY SYS-GEN,Credit,14.32,,8374.98,000000000,631,OTHER,Interest",
  "9/25/2026,CHQ PAID,Debit,,1600.00,8360.66,000605001,777,CHEQUE_PAYMENT,",
  "9/24/2026,MERCHANT SETTLEMENT - CARD,Credit,2250.00,,9960.66,000000000,777,CARD_SALES_SETTLEMENT,",
  "9/24/2026,CASH DEPOSIT - BR 631,Credit,300.00,,7710.66,000000000,631,CASH_SALES_DEPOSIT,",
].join("\n") + "\n"

export const BANK_TEMPLATE_LIMITS = "CSV (UTF-8) only · max 2 MB · one ended month per file"

export const BANK_TEMPLATE_COLUMNS: [string, string][] = [
  ["Date", "M/D/YYYY, as the bank prints it (9/30/2026) · every row inside the chosen month"],
  ["Description", "The bank's description, as printed"],
  ["Transaction Type", "Credit (money in) or Debit (money out)"],
  ["Credit Amount", "The amount of a Credit row · blank for a Debit"],
  ["Debit Amount", "The amount of a Debit row · blank for a Credit"],
  ["Running Balance", "The balance after the row, as printed · a minus sign for an overdraft"],
  ["Cheque Number", "As printed · 000000000 when there is no cheque · needed for a cheque payment"],
  ["Branch ID", "As printed · may be blank"],
  ["Category", "Added by you: CASH_SALES_DEPOSIT or CARD_SALES_SETTLEMENT (credits), CHEQUE_PAYMENT (debits), or OTHER for everything else"],
  ["Other Description", "Optional, for OTHER: what it was, for example Bank charges"],
]

/** "2026-10-22" → "2026-09": the last month that has ended, the one normally imported next. */
export function lastEndedMonth(today: string): string {
  const [y, m] = today.split("-").map(Number)
  return m === 1 ? `${y - 1}-12` : `${y}-${String(m - 1).padStart(2, "0")}`
}

/** The ended months a user can pick, newest first. */
export function endedMonths(today: string, count = 18): string[] {
  const months: string[] = []
  let month = lastEndedMonth(today)
  for (let i = 0; i < count; i++) {
    months.push(month)
    month = lastEndedMonth(`${month}-01`)
  }
  return months
}

/** "2026-09" → "September 2026". */
export const monthLabel = (month: string) =>
  new Date(`${month}-01T00:00:00Z`).toLocaleDateString("en-GB", { month: "long", year: "numeric", timeZone: "UTC" })

/** The last day of the month before today's (YYYY-MM-DD): Forecast vs Actual compares up to it. */
export function endOfLastMonth(today: string): string {
  const d = new Date(`${today.slice(0, 7)}-01T00:00:00Z`)
  d.setUTCDate(0)
  return d.toISOString().slice(0, 10)
}
