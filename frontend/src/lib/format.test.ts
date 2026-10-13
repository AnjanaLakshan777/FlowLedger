import { describe, expect, it } from "vitest"
import { fmt, parseDate } from "./format"

describe("date display (DD/MM/YYYY)", () => {
  it("formats dates, weekdays, date-times and ranges as DD/MM/YYYY", () => {
    expect(fmt.date("2026-10-26")).toBe("26/10/2026")
    expect(fmt.short("2026-10-26")).toBe("26/10/2026")
    expect(fmt.day("2026-10-26")).toBe("Mon 26/10/2026")
    expect(fmt.dateTime("2026-10-10T16:42")).toBe("10/10/2026, 16:42")
    expect(fmt.dateTimeFull("2026-10-10T16:42")).toBe("10/10/2026, 16:42")
    expect(fmt.range("2026-10-12", "2026-10-18")).toBe("12/10/2026 – 18/10/2026")
  })
  it("uses a short DD/MM tick only for chart axes", () => {
    expect(fmt.axisDate("2026-01-03")).toBe("03/01")
  })
  it("shows a dash for missing dates", () => {
    expect(fmt.date(null)).toBe("—")
    expect(fmt.day(undefined)).toBe("—")
  })
  it("keeps values ISO internally: display does not change parsing", () => {
    expect(parseDate("2026-12-31").toISOString().slice(0, 10)).toBe("2026-12-31")
  })
})
