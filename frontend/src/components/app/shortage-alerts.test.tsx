import { renderToString } from "react-dom/server"
import { describe, expect, it } from "vitest"
import type { Cheque } from "@/data/types"
import { buildForecast } from "@/lib/forecast"
import { ShortageAlerts } from "./shortage-alerts"

// Opening LKR 100,000 with one LKR 500,000 cheque clearing Wed 14/10 → short every day from 14/10 to the end of week 2.
const cheque: Cheque = {
  cheque_no: "009901", payee: "Test Supplier", category: "Supplier Payment", amount: 500_000, cheque_date: "2026-10-13",
  expected_clear_date: "2026-10-14", status: "Scheduled", notes: "",
}
const f = buildForecast({ openingBalance: 100_000, start: "2026-10-12", weeks: 2, sales: [], cheques: [cheque] })
const html = (initial?: number) => renderToString(<ShortageAlerts days={f.days} initial={initial} />).replaceAll("<!-- -->", "")
const rowDates = (h: string) => [...h.matchAll(/text-\[13px\] font-medium">(\w{3} \d{2}\/\d{2}\/\d{4})</g)].map((m) => m[1])

describe("ShortageAlerts", () => {
  it("shows one row per short date, in date order, starting with the first five", () => {
    const shortDays = f.days.filter((d) => d.closing < 0)
    expect(shortDays).toHaveLength(12) // 14/10 – 25/10
    const h = html()
    expect(h).toContain("Upcoming Cash Shortage warnings")
    expect(h).not.toMatch(/warnings \(\d+ days?\)/) // the day count isn't repeated in the title
    expect(rowDates(h)).toEqual(["Wed 14/10/2026", "Thu 15/10/2026", "Fri 16/10/2026", "Sat 17/10/2026", "Sun 18/10/2026"])
    expect(h).toContain("Show all dates")
  })

  it("can show every short date", () => {
    expect(rowDates(html(100))).toHaveLength(12)
  })

  it("marks the first short day and the days that continue it", () => {
    const h = html()
    expect(h).toContain("First day short")
    expect(h).toContain("Still short since Wed 14/10/2026")
  })

  it("explains each day with figures that add up, never a negative balance", () => {
    const h = html()
    expect(h).toContain("Started the day with LKR 100,000; expected receipts LKR 0 came in and expected cheque outflows LKR 500,000 went out, leaving a Cash Shortage of LKR 400,000.")
    expect(h).toContain("Started the day LKR 400,000 short;")
    expect(h).not.toMatch(/−LKR/)
  })

  it("renders nothing when no day is short", () => {
    const ok = buildForecast({ openingBalance: 1_000_000, start: "2026-10-12", weeks: 1, sales: [], cheques: [] })
    expect(renderToString(<ShortageAlerts days={ok.days} />)).toBe("")
  })
})
