import { describe, expect, it } from "vitest"
import * as seed from "@/data"
import { buildCalendar, canStep, lkDate, monthGrid, monthLabel, navigate, weekdayLabel, weekStart, type CalendarNav } from "./calendar"
import { buildForecast, forecastStart } from "./forecast"
import { parseDate } from "./format"

describe("calendar layout", () => {
  it("uses Monday-to-Sunday weeks that cover the whole month", () => {
    for (const m of ["2026-02-01", "2026-10-01", "2026-11-01", "2027-01-01"]) {
      const rows = monthGrid(m)
      expect(rows.every((r) => r.length === 7 && parseDate(r[0]).getUTCDay() === 1 && parseDate(r[6]).getUTCDay() === 0)).toBe(true)
      expect(rows.flat()).toContain(m)
    }
    expect(monthGrid("2026-10-01")[0][0]).toBe("2026-09-28")
    expect(monthGrid("2026-10-01").at(-1)![6]).toBe("2026-11-01")
  })
  it("starts a week on Monday, including from a Sunday", () => {
    expect(weekStart("2026-10-11")).toBe("2026-10-05")
    expect(weekStart("2026-10-12")).toBe("2026-10-12")
  })
  it("presents dates as DD/MM/YYYY with English Gregorian labels", () => {
    expect(lkDate("2026-10-23")).toBe("23/10/2026")
    expect(weekdayLabel("2026-10-26", "long")).toBe("Monday")
    expect(weekdayLabel("2026-10-26")).toBe("Mon")
    expect(monthLabel("2026-10-26")).toBe("October 2026")
  })
})

describe("calendar navigation", () => {
  const first = "2026-09-28", last = "2027-01-10"
  const at = (over: Partial<CalendarNav> = {}): CalendarNav => ({ mode: "month", anchor: "2026-10-10", selected: "2026-10-10", ...over })

  it("moves to the next and previous month", () => {
    expect(navigate(at(), { type: "step", n: 1 }, first, last)).toMatchObject({ anchor: "2026-11-01", selected: "2026-11-01" })
    expect(navigate(at({ anchor: "2026-11-15" }), { type: "step", n: -1 }, first, last).anchor).toBe("2026-10-01")
  })
  it("moves a week at a time in week view, Monday first", () => {
    expect(navigate(at({ mode: "week" }), { type: "step", n: 1 }, first, last)).toMatchObject({ anchor: "2026-10-12", selected: "2026-10-12" })
  })
  it("stops at the edges of the available data", () => {
    expect(canStep(at({ anchor: "2026-09-28" }), -1, first, last)).toBe(false)
    expect(canStep(at({ anchor: "2027-01-05" }), 1, first, last)).toBe(false)
    const n = at({ anchor: "2027-01-05" })
    expect(navigate(n, { type: "step", n: 1 }, first, last)).toBe(n)
  })
  it("returns to today", () => {
    expect(navigate(at({ anchor: "2026-12-01", selected: "2026-12-03" }), { type: "today", today: "2026-10-10" }, first, last))
      .toMatchObject({ anchor: "2026-10-10", selected: "2026-10-10" })
  })
  it("selects a date, switching month only when the date is outside the visible month", () => {
    expect(navigate(at(), { type: "select", date: "2026-10-23" }, first, last)).toMatchObject({ anchor: "2026-10-10", selected: "2026-10-23" })
    expect(navigate(at(), { type: "select", date: "2026-11-02" }, first, last)).toMatchObject({ anchor: "2026-11-02", selected: "2026-11-02" })
  })
  it("keeps the selection inside the data range", () => {
    expect(navigate(at(), { type: "select", date: "2027-03-01" }, first, last).selected).toBe(last)
  })
})

describe("calendar data", () => {
  const balance = seed.bankAccount!.balance!
  const sales = seed.predictedSales.filter((p) => !seed.dailySales.some((s) => s.sales_date === p.date)).map((p) => ({ ...p, actual: false }))
  const f = buildForecast({ openingBalance: balance, start: forecastStart(seed.TODAY), sales, cheques: seed.cheques })
  const cal = buildCalendar({ today: seed.TODAY, actualBalance: balance, transactions: seed.transactions, forecastDays: f.days })
  const days = [...cal.days.values()]

  it("keeps confirmed bank transactions and forecast items on separate days", () => {
    expect(days.filter((d) => d.date <= seed.TODAY).every((d) => d.basis === "actual" && d.events.every((e) => e.status !== "expected"))).toBe(true)
    expect(days.filter((d) => d.date > seed.TODAY).every((d) => d.basis === "forecast" && d.events.every((e) => e.status === "expected"))).toBe(true)
  })
  it("closes today at the Actual Bank Balance and chains every day's opening to the previous closing", () => {
    expect(cal.days.get(seed.TODAY)!.closing).toBe(balance)
    const sorted = days.sort((a, b) => a.date.localeCompare(b.date))
    expect(sorted.every((d, i) => i === 0 || Math.abs(d.opening - sorted[i - 1].closing) < 0.02)).toBe(true)
  })
  it("shows forecast days exactly as the forecast calculated them", () => {
    expect(f.days.every((d) => cal.days.get(d.date)!.closing === d.closing)).toBe(true)
  })
})
