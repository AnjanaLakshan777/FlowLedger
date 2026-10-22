import { describe, expect, it } from "vitest"
import { BANK_TEMPLATE_COLUMNS, BANK_TEMPLATE_CSV, endOfLastMonth, endedMonths, lastEndedMonth, monthLabel } from "./bank-template"

describe("monthly bank template", () => {
  it("has a header row with exactly the documented columns", () => {
    expect(BANK_TEMPLATE_CSV.split("\n")[0].split(",")).toEqual(BANK_TEMPLATE_COLUMNS.map(([c]) => c))
  })

  it("lists the bank's rows newest first with a running balance that follows from each amount", () => {
    const rows = BANK_TEMPLATE_CSV.trim().split("\n").slice(1).map((l) => l.split(","))
    for (let i = 0; i < rows.length - 1; i++) {
      const [, , type, credit, debit, balance] = rows[i]
      const signed = type === "Credit" ? Number(credit) : -Number(debit)
      expect(Number((Number(rows[i + 1][5]) + signed).toFixed(2))).toBe(Number(balance))
    }
  })
})

describe("months", () => {
  it("offers only months that have ended, newest first", () => {
    expect(lastEndedMonth("2026-10-22")).toBe("2026-09")
    expect(lastEndedMonth("2026-01-05")).toBe("2025-12")
    expect(endedMonths("2026-02-10", 3)).toEqual(["2026-01", "2025-12", "2025-11"])
  })

  it("names a month and finds the end of last month", () => {
    expect(monthLabel("2026-09")).toBe("September 2026")
    expect(endOfLastMonth("2026-10-22")).toBe("2026-09-30")
    expect(endOfLastMonth("2026-03-01")).toBe("2026-02-28")
    expect(endOfLastMonth("2026-01-15")).toBe("2025-12-31")
  })
})
