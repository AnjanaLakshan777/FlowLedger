import { describe, expect, it } from "vitest"
import type { ApiHoliday } from "@/lib/api/flowledger"
import { byDate, HOLIDAY_TEMPLATE_CSV, holidayKinds, isBankHoliday } from "./holidays"

const h = (date: string, name: string, over: Partial<ApiHoliday> = {}): ApiHoliday => ({
  date, name, bankHoliday: true, publicHoliday: true, mercantileHoliday: false, poyaDay: false, ...over,
})

describe("holidays", () => {
  it("groups several holidays on one date", () => {
    const map = byDate([h("2026-05-01", "Vesak Full Moon Poya Day", { poyaDay: true }), h("2026-05-01", "May Day"), h("2026-12-25", "Christmas Day")])
    expect(map.get("2026-05-01")).toHaveLength(2)
    expect(map.get("2026-12-25")?.[0].name).toBe("Christmas Day")
  })

  it("names the Central Bank codes and Poya days", () => {
    expect(holidayKinds(h("2026-05-01", "Vesak Full Moon Poya Day", { poyaDay: true }))).toEqual(["Bank holiday", "Public holiday", "Poya day"])
    expect(holidayKinds(h("2026-12-25", "Christmas Day", { mercantileHoliday: true }))).toEqual(["Bank holiday", "Public holiday", "Mercantile holiday"])
    expect(isBankHoliday([h("2026-12-25", "Christmas Day")])).toBe(true)
    expect(isBankHoliday([h("2026-12-25", "X", { bankHoliday: false })])).toBe(false)
    expect(isBankHoliday(undefined)).toBe(false)
  })

  it("offers a template with the server's header", () => {
    expect(HOLIDAY_TEMPLATE_CSV.split("\n")[0]).toBe("date,name,codes")
  })
})
