import { renderToString } from "react-dom/server"
import { describe, expect, it } from "vitest"
import { AppStateProvider } from "./app-state"
import { CashFlowCalendar } from "./cash-flow-calendar"

// Server-rendered output (no browser in the test runner): checks structure and the selected-date panel.
const render = (props: Parameters<typeof CashFlowCalendar>[0] = {}) =>
  renderToString(<AppStateProvider><CashFlowCalendar {...props} /></AppStateProvider>)
// React inserts <!-- --> between adjacent text nodes in server output
const panel = (html: string) => html.slice(html.indexOf('aria-label="Details for'), html.indexOf("</aside>")).replaceAll("<!-- -->", "")
const headers = (html: string) => [...html.matchAll(/<abbr title="([^"]+)"/g)].map((m) => m[1])

describe("CashFlowCalendar", () => {
  it("renders a Monday-to-Sunday month grid with today marked", () => {
    const html = render()
    expect(headers(html)).toEqual(["Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday"])
    expect(html).toContain("October 2026")
    expect((html.match(/aria-current="date"/g) ?? []).length).toBe(1)
    expect(html).toContain('aria-label="Saturday 10/10/2026:')
  })

  it("shows an actual day's bank data in the selected-date panel", () => {
    const p = panel(render({ initialSelected: "2026-10-09" }))
    expect(p).toContain("Friday 09/10/2026")
    expect(p).toContain(">Actual<")
    expect(p).toContain("Opening Actual Bank Balance")
    expect(p).toContain("Confirmed")
    expect(p).not.toContain("Expected receipts")
  })

  it("shows a forecast day as expected values, never as a confirmed balance", () => {
    const p = panel(render({ initialSelected: "2026-10-14" }))
    expect(p).toContain("Wednesday 14/10/2026")
    expect(p).toContain(">Forecast<")
    expect(p).toContain("Expected receipts")
    expect(p).toContain("Expected cheque outflows")
    expect(p).toContain("Expected closing balance")
    expect(p).not.toContain("Actual Bank Balance")
    expect(p).toContain("Add Cheque Payment")
  })

  it("shows a shortage once, as a Cash Shortage warning, with no negative balance", () => {
    const p = panel(render({ initialSelected: "2026-10-28" }))
    expect(p).toContain("Cash Shortage: LKR")
    expect(p).not.toContain("Expected closing balance")
    expect(p).not.toMatch(/−LKR/)
    expect((p.match(/Cash Shortage:/g) ?? []).length).toBe(1) // one warning; "Cash Shortage carried in" is the opening figure
  })

  it("renders a 7-day week view starting on Monday", () => {
    const html = render({ initialSelected: "2026-10-21", initialMode: "week" })
    expect(html).toContain("19/10/2026 – 25/10/2026")
    const days = [...html.matchAll(/aria-label="(\w+day) \d{2}\/10\/2026:/g)].map((m) => m[1])
    expect(days).toEqual(["Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday"])
  })
})
