import type {
  BankAccount, BankTransaction, Cheque, ChequeCategory, DailySale, ForecastDay, ForecastPayment, ForecastVsActual, ForecastWeek, Shortage,
} from "@/data/types"
import { daysBetween } from "@/lib/format"
import type {
  ApiBalance, ApiBankAccount, ApiCheque, ApiChequeCategory, ApiDailySale, ApiStatementImport, ApiTransaction, ChequeRequest, ForecastRun,
  ForecastVsActual as ApiForecastVsActual, TransactionCategory,
} from "./flowledger"

/**
 * Maps backend responses onto the shapes the existing screens render. Nothing here forecasts or
 * guesses: amounts, dates and statuses are the backend's own. Weeks are 7-day groups of the
 * backend's days, counted from the horizon start the backend returned.
 */

const ZONE = "Asia/Colombo"

/** Today's date in Colombo, as YYYY-MM-DD. */
export const colomboToday = (now = new Date()) =>
  new Intl.DateTimeFormat("en-CA", { timeZone: ZONE, year: "numeric", month: "2-digit", day: "2-digit" }).format(now)

/** An instant as Colombo local "YYYY-MM-DDTHH:mm", the form the date formatters expect. */
export function colomboLocal(instant: string | Date) {
  const parts = Object.fromEntries(new Intl.DateTimeFormat("en-CA", {
    timeZone: ZONE, year: "numeric", month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit", hourCycle: "h23",
  }).formatToParts(typeof instant === "string" ? new Date(instant) : instant).map((p) => [p.type, p.value]))
  return `${parts.year}-${parts.month}-${parts.day}T${parts.hour}:${parts.minute}`
}

const CHEQUE_CATEGORY: Record<ApiChequeCategory, ChequeCategory> = {
  SUPPLIER_PAYMENT: "Supplier Payment", SALARY: "Salary", TAX: "Tax", OTHER_EXPENSES: "Other Expenses",
}
const API_CHEQUE_CATEGORY = Object.fromEntries(Object.entries(CHEQUE_CATEGORY).map(([k, v]) => [v, k])) as Record<ChequeCategory, ApiChequeCategory>

export const CATEGORY_LABEL: Record<TransactionCategory, string> = {
  CASH_SALES_DEPOSIT: "Cash sales deposit", CARD_SALES_SETTLEMENT: "Card sales settlement", CHEQUE_PAYMENT: "Cheque payment",
  OTHER: "Other", UNCLASSIFIED: "Unclassified",
}
// The calendar picks its icon from these transaction types.
const CALENDAR_TYPE: Partial<Record<TransactionCategory, string>> = {
  CASH_SALES_DEPOSIT: "Cash deposit", CARD_SALES_SETTLEMENT: "Card settlement transfer", CHEQUE_PAYMENT: "Cheque",
}

export function toCheque(c: ApiCheque): Cheque {
  return {
    id: c.id, version: c.version, cheque_no: c.chequeNumber, payee: c.payeeName, category: CHEQUE_CATEGORY[c.category], amount: c.amount,
    cheque_date: c.issueDate, expected_clear_date: c.expectedClearingDate, status: c.status === "CANCELLED" ? "Cancelled" : "Scheduled",
    notes: c.notes ?? "",
  }
}

export const toChequeRequest = (c: Cheque): ChequeRequest => ({
  chequeNumber: c.cheque_no, payeeName: c.payee, category: API_CHEQUE_CATEGORY[c.category], amount: c.amount, issueDate: c.cheque_date,
  expectedClearingDate: c.expected_clear_date, notes: c.notes.trim() || null, version: c.version,
})

export function toDailySale(s: ApiDailySale, currentUserId: string | null): DailySale {
  const enteredAt = colomboLocal(s.recordedAt)
  return {
    sales_date: s.saleDate, cash_sales: s.cashSalesAmount, card_sales: s.cardSalesAmount, total: s.totalSalesAmount,
    entered_at: enteredAt,
    // The sales API returns the recording user's ID only, not a name.
    entered_by: s.recordedBy === currentUserId ? "You" : "Another user",
    // Imported history is entered long after the sale date by design; it is not a backdated entry.
    entry_type: s.entrySource === "HISTORICAL_IMPORT" ? "Imported" : daysBetween(s.saleDate, enteredAt.slice(0, 10)) >= 2 ? "Backdated" : "Standard",
    note: "",
  }
}

export function toTransaction(t: ApiTransaction): BankTransaction {
  return {
    txn_id: t.id, txn_date: t.transactionDate, value_date: "", description: t.description ?? "",
    reference: [CATEGORY_LABEL[t.category], t.chequeNumber && `Cheque ${t.chequeNumber}`].filter(Boolean).join(" · "),
    amount: t.direction === "CREDIT" ? t.amount : -t.amount,
    txn_type: CALENDAR_TYPE[t.category] ?? CATEGORY_LABEL[t.category],
    match_status: "Matched", matched_record: "", confidence: null, note: "", balance_after: t.runningBalance,
  }
}

/** The company current account with its confirmed balance (null if none) and latest statement import. */
export function toBankAccount(account: ApiBankAccount, balance: ApiBalance | null, imports: ApiStatementImport[]): BankAccount {
  const latest = imports.filter((i) => i.bankAccountId === account.id).sort((a, b) => b.importedAt.localeCompare(a.importedAt))[0]
  const lastImported = imports.filter((i) => i.bankAccountId === account.id && i.status === "IMPORTED")
    .sort((a, b) => b.importedAt.localeCompare(a.importedAt))[0]
  return {
    bank: account.bankName, account_name: account.accountName, account_type: "Current",
    account_masked: account.accountNumberLast4 ? `•••• ${account.accountNumberLast4}` : "Not provided",
    branch: "Not provided", currency: account.currency,
    balance: balance?.balanceAmount ?? null,
    balance_as_of: balance ? colomboLocal(balance.balanceAsOf) : "",
    sync_status: latest?.status === "FAILED" ? "Error" : "Synced",
    last_sync: lastImported ? colomboLocal(lastImported.importedAt) : "",
    integration: account.integrationMode === "STATEMENT_IMPORT" ? "Bank statement import" : account.integrationMode,
    error: latest?.status === "FAILED" ? `The latest statement file (${latest.filename}) was rejected: ${latest.errorSummary ?? "invalid file"}.` : "",
  }
}

export interface LiveForecast { days: ForecastDay[]; weeks: ForecastWeek[]; shortages: Shortage[] }

const WEEKDAY = ["Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"]

/** The backend's 84 days in the shapes of the forecast screens. Cheque payees come from `cheques` when loaded. */
export function forecastFromRun(run: ForecastRun, cheques: Cheque[]): LiveForecast {
  const byId = new Map(cheques.filter((c) => c.id).map((c) => [c.id!, c]))
  const estimated = run.run.inflowEstimateStatus === "ESTIMATED"
  const days: ForecastDay[] = run.days.map((d, i) => {
    const payments: ForecastPayment[] = run.cheques
      .filter((c) => c.expectedClearingDate === d.date)
      .map((c) => {
        const known = byId.get(c.chequeId)
        return { cheque_no: c.chequeNumber, payee: known?.payee ?? `Cheque #${c.chequeNumber}`, category: known?.category ?? "Other Expenses", amount: c.amount, date: d.date }
      })
      .sort((a, b) => b.amount - a.amount)
    const cash = d.expectedCashSalesDeposits ?? 0
    const card = d.expectedCardSalesSettlements ?? 0
    return {
      date: d.date, week: Math.floor(i / 7) + 1, weekday: WEEKDAY[new Date(d.date + "T00:00:00Z").getUTCDay()],
      opening: d.openingBalance, cash_sales: cash, card_sales: card, receipts: d.expectedSalesInflows ?? 0,
      cheque_out: d.expectedChequeOutflows, cheques: d.expectedChequeCount, closing: d.closingBalance,
      // no likely range: the backend does not estimate one
      low: d.closingBalance, high: d.closingBalance,
      top_outflows: payments.slice(0, 3).map((p) => `${p.payee} cheque ${p.cheque_no}`).join("; "),
      arrivals: [
        ...(cash > 0 ? [{ kind: "Cash" as const, sales_date: null, amount: cash, actual: false }] : []),
        ...(card > 0 ? [{ kind: "Card" as const, sales_date: null, amount: card, actual: false }] : []),
      ],
      payments,
      inflows_estimated: estimated,
    }
  })
  return { days, weeks: forecastWeeks(days), shortages: forecastShortages(days) }
}

export function forecastWeeks(days: ForecastDay[]): ForecastWeek[] {
  const weeks: ForecastWeek[] = []
  for (let w = 1; w <= Math.ceil(days.length / 7); w++) {
    const ds = days.filter((d) => d.week === w)
    const lo = ds.reduce((m, d) => (d.closing < m.closing ? d : m), ds[0])
    const last = ds[ds.length - 1]
    const total = (k: "cash_sales" | "card_sales" | "receipts" | "cheque_out") => round(ds.reduce((t, d) => t + d[k], 0))
    weeks.push({
      week: w, week_start: ds[0].date, week_end: last.date, opening: ds[0].opening,
      cash_sales: total("cash_sales"), card_sales: total("card_sales"), receipts: total("receipts"), cheque_out: total("cheque_out"),
      closing: last.closing, low: last.closing, high: last.closing, lowest_balance: lo.closing, lowest_date: lo.date,
      shortage_days: ds.filter((d) => d.closing < 0).length,
      first_shortage_date: ds.find((d) => d.closing < 0)?.date ?? null, shortage_amount: lo.closing < 0 ? round(-lo.closing) : 0,
    })
  }
  return weeks
}

/** Consecutive days below zero, grouped for the shortage explanations. */
export function forecastShortages(days: ForecastDay[]): Shortage[] {
  const shortages: Shortage[] = []
  let run: ForecastDay[] = []
  const flush = () => {
    if (!run.length) return
    const first = run[0]
    const lo = run.reduce((m, d) => (d.closing < m.closing ? d : m), run[0])
    const span = days.slice(days.indexOf(first), days.indexOf(lo) + 1)
    const weekEnd = days.filter((d) => d.week === first.week).at(-1)!
    shortages.push({
      shortage_id: `SH-${shortages.length + 1}`, first_date: first.date, lowest_date: lo.date, lowest_balance: lo.closing,
      shortage_amount: round(-lo.closing), days_negative: run.length, week: first.week, week_end_balance: weekEnd.closing,
      opening_before: first.opening, receipts_in_window: round(span.reduce((t, d) => t + d.receipts, 0)),
      payments_in_window: round(span.reduce((t, d) => t + d.cheque_out, 0)),
      payments: span.flatMap((d) => d.payments).sort((a, b) => b.amount - a.amount),
    })
    run = []
  }
  for (const d of days) {
    if (d.closing < 0) run.push(d)
    else flush()
  }
  flush()
  return shortages
}

/** Forecast vs Actual by 7-day period from the run's start; only days with bank-confirmed actuals count. */
export function forecastVsActualWeeks(fva: ApiForecastVsActual): ForecastVsActual[] {
  const rows: ForecastVsActual[] = []
  for (let i = 0; i < fva.days.length; i += 7) {
    const block = fva.days.slice(i, i + 7)
    const compared = block.filter((d) => d.actualsAvailable)
    if (!compared.length) continue
    const last = compared[compared.length - 1]
    const forecastIn = compared.some((d) => d.forecastSalesInflows == null) ? null : round(compared.reduce((t, d) => t + (d.forecastSalesInflows ?? 0), 0))
    const actualIn = round(compared.reduce((t, d) => t + (d.actualSalesInflows ?? 0), 0))
    const forecastOut = round(compared.reduce((t, d) => t + d.forecastChequeOutflows, 0))
    const actualOut = round(compared.reduce((t, d) => t + (d.actualChequeOutflows ?? 0), 0))
    rows.push({
      week_start: block[0].date, week_end: block[block.length - 1].date,
      forecast_in: forecastIn, actual_in: actualIn, diff_in: forecastIn == null ? null : round(actualIn - forecastIn),
      forecast_out: forecastOut, actual_out: actualOut, diff_out: round(actualOut - forecastOut),
      forecast_closing: last.forecastClosingBalance, actual_closing: last.actualClosingBalance,
      diff_closing: last.actualClosingBalance == null ? null : round(last.actualClosingBalance - last.forecastClosingBalance),
      days_compared: compared.length,
    })
  }
  return rows
}

const round = (v: number) => Math.round(v * 100) / 100

