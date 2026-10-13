const nf2 = new Intl.NumberFormat("en-US", { minimumFractionDigits: 2, maximumFractionDigits: 2 })
const nf0 = new Intl.NumberFormat("en-US", { maximumFractionDigits: 0 })
const DAY = ["Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"]
const MINUS = "−"

/** Parse "YYYY-MM-DD" (or an ISO datetime) as a UTC calendar date. */
export const parseDate = (s: string) => {
  const [y, m, d] = s.slice(0, 10).split("-").map(Number)
  return new Date(Date.UTC(y, m - 1, d))
}
const sign = (v: number) => (v < 0 ? MINUS : "")
/** DD/MM/YYYY */
const dmy = (s: string) => {
  const d = parseDate(s)
  return `${String(d.getUTCDate()).padStart(2, "0")}/${String(d.getUTCMonth() + 1).padStart(2, "0")}/${d.getUTCFullYear()}`
}
const ok = (v: unknown): v is number => typeof v === "number" && !Number.isNaN(v)

export const fmt = {
  /** 1,234,567.00 */
  amt: (v: number | null | undefined) => (ok(v) ? sign(v) + nf2.format(Math.abs(v)) : "—"),
  /** +1,234.00 / −1,234.00 */
  signed: (v: number | null | undefined) => (ok(v) ? (v < 0 ? MINUS : "+") + nf2.format(Math.abs(v)) : "—"),
  /** LKR 1,234,567.00 */
  lkr: (v: number | null | undefined) => (ok(v) ? sign(v) + "LKR " + nf2.format(Math.abs(v)) : "—"),
  /** LKR 1,234,567 */
  lkr0: (v: number | null | undefined) => (ok(v) ? sign(v) + "LKR " + nf0.format(Math.abs(v)) : "—"),
  /** 1.23M / 450K */
  compact: (v: number | null | undefined) => {
    if (!ok(v)) return "—"
    const a = Math.abs(v)
    const s = a >= 1e6 ? (a / 1e6).toFixed(2) + "M" : a >= 1e3 ? (a / 1e3).toFixed(0) + "K" : a.toFixed(0)
    return sign(v) + s
  },
  axis: (v: number) => {
    const a = Math.abs(v)
    return sign(v) + (a >= 1e6 ? (a / 1e6).toFixed(a % 1e6 === 0 ? 0 : 1) + "M" : a >= 1e3 ? (a / 1e3).toFixed(0) + "K" : String(a))
  },
  int: (v: number) => nf0.format(v),
  pct: (v: number) => (ok(v) ? (v > 0 ? "+" : v < 0 ? MINUS : "") + Math.abs(v * 100).toFixed(1) + "%" : "—"),
  // Dates are shown as DD/MM/YYYY everywhere (Sri Lankan presentation); values stay ISO YYYY-MM-DD internally.
  /** 10/10/2026 */
  date: (s?: string | null) => (s ? dmy(s) : "—"),
  /** 10/10/2026 (same as `date`; kept for existing callers) */
  short: (s?: string | null) => (s ? dmy(s) : "—"),
  /** Sat 10/10/2026 */
  day: (s?: string | null) => (s ? `${DAY[parseDate(s).getUTCDay()]} ${dmy(s)}` : "—"),
  /** 10/10/2026, 16:42 */
  dateTime: (s?: string | null) => (s ? `${dmy(s)}, ${s.slice(11, 16)}` : "—"),
  /** 10/10/2026, 16:42 */
  dateTimeFull: (s?: string | null) => (s ? `${dmy(s)}, ${s.slice(11, 16)}` : "—"),
  /** 12/10/2026 – 18/10/2026 */
  range: (a: string, b: string) => `${dmy(a)} – ${dmy(b)}`,
  /** 12/10 — chart axis ticks only, where a full date would crowd the axis */
  axisDate: (s: string) => dmy(s).slice(0, 5),
}

export const daysBetween = (a: string, b: string) => Math.round((parseDate(b).getTime() - parseDate(a).getTime()) / 864e5)
export const addDays = (s: string, n: number) => {
  const d = parseDate(s)
  d.setUTCDate(d.getUTCDate() + n)
  return d.toISOString().slice(0, 10)
}
export const isWeekend = (s: string) => [0, 6].includes(parseDate(s).getUTCDay())
/** Add business days (Mon–Fri) and roll forward off a weekend. */
export const addBusinessDays = (s: string, n: number) => {
  let d = s
  let added = 0
  while (added < n || isWeekend(d)) {
    d = addDays(d, 1)
    if (!isWeekend(d)) added++
  }
  return d
}
export const parseAmount = (s: string): number | null => {
  if (s.trim() === "") return null
  const v = Number(s.replace(/[, ]/g, ""))
  return Number.isNaN(v) ? NaN : v
}
export const initials = (name: string) => name.split(" ").map((p) => p[0]).slice(0, 2).join("").toUpperCase()
export const sum = <T,>(rows: T[], f: (r: T) => number) => rows.reduce((s, r) => s + f(r), 0)
