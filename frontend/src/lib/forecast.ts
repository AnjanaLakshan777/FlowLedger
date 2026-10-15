import type { BankTransaction, Cheque, DailySale, ForecastArrival, ForecastDay, ForecastWeek, Shortage } from "@/data/types"
import { isUpcoming } from "./cheques"
import { addDays, parseDate } from "./format"

export { isUpcoming }

/**
 * DEMONSTRATION forecast for the fictional data in src/data (demo mode and frontend tests only). Live screens show the
 * forecast service's results and never calculate a forecast in the browser.
 * - Opening balance = Actual Bank Balance of the company's one current account (never includes expected receipts).
 * - Only cash sales and card payments are predicted. Customer cheques and direct transfers are not.
 * - Sales are counted on the date they are expected to reach the company current account, not the sales date:
 *   Mon–Thu sales arrive the next morning; Fri, Sat and Sun sales arrive on Monday (cash via deposit,
 *   card via the card settlement account). Holidays and bank delays can shift this — the backend should apply them.
 * - Cash deposits and card settlement transfers (from the separate card settlement account) are the arrival of sales
 *   already counted, never new income. The card settlement account is not a company bank account in FlowLedger.
 * - Sales already seen in bank data are part of the Actual Bank Balance and are not expected again.
 * - Outflows = scheduled cheques, deducted once on their expected clearing date within the forecast. A cheque whose
 *   clearing date has already passed is not deducted, and it is not marked paid either: it stays Scheduled.
 *   Cancelled cheques are excluded. (No bank-based clearing or bounce detection yet.)
 * - A shortage is any day whose expected balance is below LKR 0 — even if the week ends positive.
 */

/** Forecast horizon in Monday–Sunday weeks. */
export const FORECAST_WEEKS = 12

export interface SaleForForecast { date: string; cash_sales: number; card_sales: number; actual: boolean }
export interface Adjustment { date: string; inflow?: number; outflow?: number }
export interface ForecastResult { days: ForecastDay[]; weeks: ForecastWeek[]; shortages: Shortage[] }

const DAY = ["Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"]
const r2 = (v: number) => Math.round(v * 100) / 100

export type ReceiptKind = "Cash" | "Card"

/**
 * Days from the sales date to current-account arrival, indexed by sales weekday (Sun…Sat).
 * Cash: deposited the next morning; Friday–Sunday sales on Monday.
 * Card: transferred from the card settlement account on the same schedule (per the business rules).
 * The backend should own this configuration and apply holidays and bank delays.
 */
export const ARRIVAL_SCHEDULE: Record<ReceiptKind, readonly number[]> = {
  Cash: [1, 1, 1, 1, 1, 3, 2],
  Card: [1, 1, 1, 1, 1, 3, 2],
}

/** Normal current-account arrival date for `kind` sales made on `salesDate`. */
export function expectedArrivalDate(salesDate: string, kind: ReceiptKind = "Cash") {
  return addDays(salesDate, ARRIVAL_SCHEDULE[kind][parseDate(salesDate).getUTCDay()])
}


/** First Monday after `today` — forecast weeks run Monday to Sunday. */
export function forecastStart(today: string) {
  let d = addDays(today, 1)
  while (parseDate(d).getUTCDay() !== 1) d = addDays(d, 1)
  return d
}

export interface SalesReceipt {
  sales_date: string; kind: ReceiptKind; amount: number; expected_arrival: string
  /** "confirmed" = matched to a bank transaction; "review" = in bank data, match not yet confirmed; "not_in_bank" = not in bank data yet (still expected). */
  state: "confirmed" | "review" | "not_in_bank"; bank_date: string | null; txn_id: string | null
}

/** Sales entered in FlowLedger beside the bank transaction (if any) that brought the money in. */
const MON3 = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"]
/**
 * Date as it appears in the bank data's matched-record text ("Cash sales 05 Oct"). This is a matching key, not a display
 * format — it must stay independent of fmt.* so changing how dates are shown can never break the match.
 */
const recordDate = (iso: string) => { const d = parseDate(iso); return `${String(d.getUTCDate()).padStart(2, "0")} ${MON3[d.getUTCMonth()]}` }

export function salesReceipts(sales: DailySale[], txns: BankTransaction[], from: string): SalesReceipt[] {
  const out: SalesReceipt[] = []
  for (const s of sales.filter((x) => x.sales_date >= from)) {
    for (const kind of ["Cash", "Card"] as const) {
      const amount = kind === "Cash" ? s.cash_sales : s.card_sales
      if (!amount) continue
      const label = `${kind} sales ${recordDate(s.sales_date)}`
      const t = txns.find((x) => x.matched_record === label && (x.match_status === "Matched" || x.match_status === "Review"))
      out.push({
        sales_date: s.sales_date, kind, amount, expected_arrival: expectedArrivalDate(s.sales_date, kind),
        state: !t ? "not_in_bank" : t.match_status === "Matched" ? "confirmed" : "review", bank_date: t?.txn_date ?? null, txn_id: t?.txn_id ?? null,
      })
    }
  }
  return out
}

export function buildForecast(opts: {
  openingBalance: number
  start: string
  /** Cheques clearing on or before this date are not deducted (default: the day before `start`); they are never marked paid. */
  today?: string
  weeks?: number
  /** Entered sales not yet seen in bank data, plus predicted sales for dates without an entry. */
  sales: SaleForForecast[]
  cheques: Cheque[]
  adjustments?: Adjustment[] // deferred scenario analysis only
}): ForecastResult {
  const { openingBalance, start, weeks: nWeeks = FORECAST_WEEKS, sales, cheques, adjustments = [] } = opts
  const upcoming = cheques.filter((c) => isUpcoming(c, opts.today ?? addDays(start, -1)))
  // anything due before the first forecast day (late arrivals, overdue cheques) is placed on day one
  const onDay = (d: string, i: number, date: string) => d === date || (i === 0 && d < start)
  const days: ForecastDay[] = []
  let bal = openingBalance
  let variance = 0
  let cumPredicted = 0
  for (let i = 0; i < nWeeks * 7; i++) {
    const date = addDays(start, i)
    const adj = adjustments.filter((a) => a.date === date)
    const madeToday = sales.filter((s) => s.date === date && !s.actual)
    const arrivals: ForecastArrival[] = []
    for (const s of sales) {
      for (const kind of ["Cash", "Card"] as const) {
        const amount = kind === "Cash" ? s.cash_sales : s.card_sales
        if (amount && onDay(expectedArrivalDate(s.date, kind), i, date)) arrivals.push({ kind, sales_date: s.date, amount, actual: s.actual })
      }
    }
    const predictedArriving = arrivals.filter((a) => !a.actual).reduce((t, a) => t + a.amount, 0)
    const receipts = arrivals.reduce((t, a) => t + a.amount, 0) + adj.reduce((t, a) => t + (a.inflow ?? 0), 0)
    const cs = upcoming.filter((c) => onDay(c.expected_clear_date, i, date))
    const out = cs.reduce((t, c) => t + c.amount, 0) + adj.reduce((t, a) => t + (a.outflow ?? 0), 0)
    const opening = bal
    bal = opening + receipts - out
    variance += (0.09 * predictedArriving) ** 2
    cumPredicted += predictedArriving
    const band = 1.28 * Math.sqrt(variance) + 0.012 * cumPredicted // ~80% range, widens with time
    const top = [...cs].sort((a, b) => b.amount - a.amount).slice(0, 3)
    days.push({
      date, week: Math.floor(i / 7) + 1, weekday: DAY[parseDate(date).getUTCDay()], opening: r2(opening),
      cash_sales: r2(madeToday.reduce((t, s) => t + s.cash_sales, 0)), card_sales: r2(madeToday.reduce((t, s) => t + s.card_sales, 0)),
      receipts: r2(receipts), cheque_out: r2(out), cheques: cs.length, closing: r2(bal), low: r2(bal - band), high: r2(bal + band),
      top_outflows: top.map((c) => `${c.payee} cheque ${c.cheque_no} (LKR ${(c.amount / 1e6).toFixed(2)}M)`).join("; "),
      arrivals,
      payments: [...cs].sort((a, b) => b.amount - a.amount).map((c) => ({ cheque_no: c.cheque_no, payee: c.payee, category: c.category, amount: c.amount, date })),
    })
  }

  const weeks: ForecastWeek[] = []
  for (let w = 1; w <= nWeeks; w++) {
    const ds = days.filter((d) => d.week === w)
    const lo = ds.reduce((m, d) => (d.closing < m.closing ? d : m), ds[0])
    const last = ds[ds.length - 1]
    const total = (k: "cash_sales" | "card_sales" | "receipts" | "cheque_out") => r2(ds.reduce((t, d) => t + d[k], 0))
    weeks.push({
      week: w, week_start: ds[0].date, week_end: last.date, opening: ds[0].opening,
      cash_sales: total("cash_sales"), card_sales: total("card_sales"), receipts: total("receipts"), cheque_out: total("cheque_out"),
      closing: last.closing, low: last.low, high: last.high, lowest_balance: lo.closing, lowest_date: lo.date,
      shortage_days: ds.filter((d) => d.closing < 0).length,
      first_shortage_date: ds.find((d) => d.closing < 0)?.date ?? null, shortage_amount: lo.closing < 0 ? r2(-lo.closing) : 0,
    })
  }

  const shortages: Shortage[] = []
  let run: ForecastDay[] = []
  const flush = () => {
    if (!run.length) return
    const firstDay = run[0]
    const lo = run.reduce((m, d) => (d.closing < m.closing ? d : m), run[0])
    const weekEnd = days.filter((d) => d.week === firstDay.week).at(-1)!
    // explanation only (detection above is unchanged), covering just the short days:
    // cash at the start of the first short day + receipts − payments up to the worst day = lowest balance
    const span = days.slice(days.indexOf(firstDay), days.indexOf(lo) + 1)
    const placed = (c: Cheque) => (c.expected_clear_date < start ? start : c.expected_clear_date)
    const payments = upcoming
      .filter((c) => placed(c) >= firstDay.date && placed(c) <= lo.date)
      .sort((a, b) => b.amount - a.amount)
      .map((c) => ({ cheque_no: c.cheque_no, payee: c.payee, category: c.category, amount: c.amount, date: placed(c) }))
    shortages.push({
      shortage_id: `SH-${shortages.length + 1}`, first_date: firstDay.date, lowest_date: lo.date, lowest_balance: lo.closing, shortage_amount: r2(-lo.closing),
      days_negative: run.length, week: firstDay.week, week_end_balance: weekEnd.closing,
      opening_before: firstDay.opening, receipts_in_window: r2(span.reduce((t, d) => t + d.receipts, 0)),
      payments_in_window: r2(span.reduce((t, d) => t + d.cheque_out, 0)), payments,
    })
    run = []
  }
  for (const d of days) {
    if (d.closing < 0) run.push(d)
    else flush()
  }
  flush()
  return { days, weeks, shortages }
}
