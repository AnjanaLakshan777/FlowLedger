import { describe, expect, it } from "vitest"
import type { Cheque } from "@/data/types"
import { toCheque, upsertCheque, validateCheque, type ChequeInput } from "./cheques"
import { buildForecast } from "./forecast"

const TODAY = "2026-10-10"
const existing: Cheque[] = [{
  cheque_no: "004535", payee: "Staff salaries", category: "Salary", amount: 9_800_000, cheque_date: "2026-10-24",
  expected_clear_date: "2026-10-26", status: "Scheduled", notes: "",
}]
const input = (over: Partial<ChequeInput> = {}): ChequeInput => ({
  category: "Supplier Payment", payee: "Test Supplier", amount: 500_000, cheque_no: "009901", cheque_date: "2026-10-21",
  expected_clear_date: "2026-10-23", notes: "", ...over,
})

describe("validateCheque", () => {
  it("accepts a complete future payment", () => {
    expect(validateCheque(input(), existing, TODAY)).toEqual({})
  })
  it("requires payee, category, cheque number, issue date and expected clearing date", () => {
    const e = validateCheque(input({ payee: " ", category: "", cheque_no: "", cheque_date: "", expected_clear_date: "" }), existing, TODAY)
    expect(Object.keys(e).sort()).toEqual(["category", "cheque_date", "cheque_no", "expected_clear_date", "payee"])
  })
  it("rejects zero, negative and missing amounts", () => {
    for (const amount of [0, -1, null, Number.NaN]) expect(validateCheque(input({ amount }), existing, TODAY).amount).toBeDefined()
  })
  it("rejects invalid dates", () => {
    expect(validateCheque(input({ cheque_date: "2026-02-30" }), existing, TODAY).cheque_date).toBeDefined()
    expect(validateCheque(input({ expected_clear_date: "23/10/2026" }), existing, TODAY).expected_clear_date).toBeDefined()
  })
  it("rejects a clearing date before the issue date", () => {
    expect(validateCheque(input({ expected_clear_date: "2026-10-20" }), existing, TODAY).expected_clear_date).toMatch(/before the cheque issue date/)
  })
  it("rejects a clearing date on or before today — that payment would already be in the Actual Bank Balance", () => {
    expect(validateCheque(input({ cheque_date: "2026-10-01", expected_clear_date: TODAY }), existing, TODAY).expected_clear_date).toMatch(/after today/)
  })
  it("rejects a duplicate cheque number, except the cheque being edited", () => {
    expect(validateCheque(input({ cheque_no: "004535" }), existing, TODAY).cheque_no).toMatch(/already recorded/)
    expect(validateCheque(input({ cheque_no: "004535" }), existing, TODAY, "004535").cheque_no).toBeUndefined()
  })
})

describe("adding and editing cheques", () => {
  it("stores a scheduled future payment with its expected clearing date", () => {
    expect(toCheque(input())).toMatchObject({ status: "Scheduled", cheque_date: "2026-10-21", expected_clear_date: "2026-10-23" })
  })
  it("adds a new cheque", () => {
    expect(upsertCheque(existing, toCheque(input())).map((c) => c.cheque_no)).toEqual(["004535", "009901"])
  })
  it("replaces the edited cheque, even when its number is corrected", () => {
    const list = upsertCheque(existing, { ...existing[0], cheque_no: "004536", amount: 9_900_000 }, "004535")
    expect(list).toHaveLength(1)
    expect(list[0]).toMatchObject({ cheque_no: "004536", amount: 9_900_000 })
  })
  it("moving the expected clearing date moves the deduction without a duplicate", () => {
    const moved = upsertCheque(existing, { ...existing[0], expected_clear_date: "2026-10-28" }, "004535")
    const f = buildForecast({ openingBalance: 0, start: "2026-10-26", today: TODAY, weeks: 1, sales: [], cheques: moved })
    expect(f.days.find((d) => d.date === "2026-10-26")!.cheque_out).toBe(0)
    expect(f.days.find((d) => d.date === "2026-10-28")!.cheque_out).toBe(9_800_000)
    expect(f.days.reduce((t, d) => t + d.cheque_out, 0)).toBe(9_800_000)
  })
})
