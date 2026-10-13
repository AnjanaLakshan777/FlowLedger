import type { BankTransaction, ForecastDay } from "@/data/types"
import { addDays, fmt, parseDate } from "./format"

/**
 * Builds one record per calendar day for the Cash Flow Calendar.
 * - Up to and including today: bank data only (Actual Bank Balance and the transactions that actually happened).
 * - After today: the forecast (expected deposits, card settlement transfers, cheque payments).
 * Actual and forecast figures are never mixed on the same day. Dates stay Gregorian ISO strings internally.
 */
export type EventKind = "cash" | "card" | "cheque" | "bank"
export type EventStatus = "actual" | "excluded" | "expected"
export interface CalendarEvent {
  id: string; kind: EventKind; status: EventStatus; title: string; detail: string; amount: number
  /** Cheque number for scheduled cheque payments. */
  chequeNo?: string
}
export interface CalendarDay {
  date: string
  /** "actual" = bank data (up to today); "forecast" = projection. Dates outside the data have no record. */
  basis: "actual" | "forecast"
  opening: number; receipts: number; outflows: number; closing: number
  events: CalendarEvent[]
}

/** DD/MM/YYYY — Sri Lankan date presentation (same as fmt.date). */
export const lkDate = (iso: string) => fmt.date(iso)

/** English labels, Gregorian names only — fixed tables rather than Intl so output doesn't vary by runtime. */
const LABELS = {
  dayShort: ["Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"],
  dayLong: ["Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday"],
  month: ["January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December"],
} as const
/** Weekday name, e.g. "Mon" / "Monday". */
export const weekdayLabel = (iso: string, style: "short" | "long" = "short") =>
  LABELS[style === "short" ? "dayShort" : "dayLong"][parseDate(iso).getUTCDay()]
/** "October 2026" — Gregorian month and year. */
export const monthLabel = (iso: string) => {
  const d = parseDate(iso)
  return `${LABELS.month[d.getUTCMonth()]} ${d.getUTCFullYear()}`
}

/** Monday on or before `iso` — calendar weeks run Monday to Sunday. */
export const weekStart = (iso: string) => addDays(iso, -((parseDate(iso).getUTCDay() + 6) % 7))
export const monthStart = (iso: string) => iso.slice(0, 8) + "01"
export const addMonths = (iso: string, n: number) => {
  const d = parseDate(monthStart(iso))
  d.setUTCMonth(d.getUTCMonth() + n)
  return d.toISOString().slice(0, 10)
}
/** The 7-day rows (Mon–Sun) that cover the month of `iso`. */
export function monthGrid(iso: string): string[][] {
  const first = monthStart(iso)
  const last = addDays(addMonths(first, 1), -1)
  const rows: string[][] = []
  for (let d = weekStart(first); d <= last; d = addDays(d, 7)) rows.push(Array.from({ length: 7 }, (_, i) => addDays(d, i)))
  return rows
}

const KIND: Record<string, EventKind> = { "Cash deposit": "cash", "Card settlement transfer": "card", Cheque: "cheque" }
const counted = (t: BankTransaction) => t.match_status !== "Excluded"

export function buildCalendar(opts: { today: string; actualBalance: number; transactions: BankTransaction[]; forecastDays: ForecastDay[] }) {
  const { today, actualBalance, transactions, forecastDays } = opts
  const days = new Map<string, CalendarDay>()
  const chrono = [...transactions].reverse() // the bank feed is newest first
  const firstTxn = chrono.find(counted)
  const firstDate = chrono[0]?.txn_date ?? today
  // Actual Bank Balance before the earliest transaction in the feed
  let bal = firstTxn?.balance_after != null ? firstTxn.balance_after - firstTxn.amount : actualBalance

  for (let d = firstDate; d <= today; d = addDays(d, 1)) {
    const dayTx = chrono.filter((t) => t.txn_date === d)
    const opening = bal
    for (const t of dayTx) if (counted(t) && t.balance_after != null) bal = t.balance_after
    const conf = dayTx.filter(counted)
    days.set(d, {
      date: d, basis: "actual", opening, closing: d === today ? actualBalance : bal,
      receipts: conf.filter((t) => t.amount > 0).reduce((s, t) => s + t.amount, 0),
      outflows: -conf.filter((t) => t.amount < 0).reduce((s, t) => s + t.amount, 0),
      events: [...dayTx].reverse().map((t) => ({
        id: t.txn_id, kind: KIND[t.txn_type] ?? "bank",
        status: t.match_status === "Excluded" ? "excluded" : "actual",
        title: t.description, detail: [t.txn_type, t.reference, t.note].filter(Boolean).join(" · "), amount: t.amount,
      })),
    })
  }

  // days between today and the first forecast day (e.g. a weekend): no expected movements
  const fStart = forecastDays[0]?.date
  for (let d = addDays(today, 1); fStart && d < fStart; d = addDays(d, 1)) {
    days.set(d, { date: d, basis: "forecast", opening: actualBalance, receipts: 0, outflows: 0, closing: actualBalance, events: [] })
  }

  for (const f of forecastDays) {
    const events: CalendarEvent[] = [
      ...f.arrivals.map((a, i) => ({
        id: `${f.date}-${a.kind}-${i}`, kind: (a.kind === "Cash" ? "cash" : "card") as EventKind, status: "expected" as const,
        title: a.kind === "Cash" ? "Expected cash deposit" : "Expected card settlement transfer",
        detail: `Sales of ${weekdayLabel(a.sales_date)} ${lkDate(a.sales_date)} · ${a.actual ? "entered, not yet in bank data" : "predicted"}`,
        amount: a.amount,
      })),
      ...f.payments.map((p) => ({
        id: `${f.date}-chq-${p.cheque_no}`, kind: "cheque" as const, status: "expected" as const,
        title: `Cheque #${p.cheque_no} · ${p.payee}`,
        detail: `${p.category} · forecast on its expected clearing date`,
        amount: -p.amount, chequeNo: p.cheque_no,
      })),
    ]
    days.set(f.date, { date: f.date, basis: "forecast", opening: f.opening, receipts: f.receipts, outflows: f.cheque_out, closing: f.closing, events })
  }

  const dates = [...days.keys()].sort()
  return { days, first: dates[0], last: dates.at(-1)! }
}

/** Screen-reader summary of a day cell. */
export function describeDay(day: CalendarDay | undefined, iso: string) {
  const date = `${weekdayLabel(iso, "long")} ${lkDate(iso)}`
  if (!day) return `${date}: no data`
  const c = (k: EventKind, s: EventStatus) => day.events.filter((e) => e.kind === k && e.status === s).length
  const parts = [
    day.closing < 0 ? `Cash Shortage ${fmt.lkr0(-day.closing)}` : `${day.basis === "actual" ? "Actual Bank Balance" : "Expected closing balance"} ${fmt.lkr0(day.closing)}`,
    c("cash", "expected") && `${c("cash", "expected")} expected cash deposit`,
    c("card", "expected") && `${c("card", "expected")} expected card settlement`,
    c("cheque", "expected") && `${c("cheque", "expected")} scheduled cheque payment`,
    day.events.filter((e) => e.status === "actual").length && `${day.events.filter((e) => e.status === "actual").length} confirmed bank transaction`,
  ].filter(Boolean)
  return `${date}: ${parts.join(", ")}`
}

// ---- navigation (pure, so it can be tested without a browser)
export type CalendarMode = "month" | "week"
export interface CalendarNav { mode: CalendarMode; anchor: string; selected: string }
export type NavAction = { type: "step"; n: number } | { type: "today"; today: string } | { type: "select"; date: string } | { type: "mode"; mode: CalendarMode }

export const clampDate = (d: string, first: string, last: string) => (d < first ? first : d > last ? last : d)
/** First day of the visible period: the 1st of the month, or the Monday of the week. */
export const periodOf = (mode: CalendarMode, d: string) => (mode === "month" ? monthStart(d) : weekStart(d))
export const canStep = (nav: CalendarNav, n: number, first: string, last: string) =>
  n < 0 ? periodOf(nav.mode, nav.anchor) > periodOf(nav.mode, first) : periodOf(nav.mode, nav.anchor) < periodOf(nav.mode, last)

/** Calendar navigation. Selection always stays inside the available data range [first, last]. */
export function navigate(nav: CalendarNav, a: NavAction, first: string, last: string): CalendarNav {
  switch (a.type) {
    case "step": {
      if (!canStep(nav, a.n, first, last)) return nav
      const anchor = nav.mode === "month" ? addMonths(nav.anchor, a.n) : addDays(weekStart(nav.anchor), 7 * a.n)
      return { ...nav, anchor, selected: clampDate(anchor, first, last) }
    }
    case "today": return { ...nav, anchor: a.today, selected: clampDate(a.today, first, last) }
    case "select": {
      const selected = clampDate(a.date, first, last)
      return { ...nav, selected, anchor: periodOf(nav.mode, selected) === periodOf(nav.mode, nav.anchor) ? nav.anchor : selected }
    }
    case "mode": return { ...nav, mode: a.mode, anchor: nav.selected }
  }
}
