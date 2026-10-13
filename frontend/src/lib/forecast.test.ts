import { describe, expect, it } from "vitest"
import type { BankTransaction, Cheque, DailySale } from "@/data/types"
import { buildForecast, expectedArrivalDate, FORECAST_WEEKS, isAssumedPaid, isUpcoming, salesReceipts, type SaleForForecast } from "./forecast"

// A small, fully controlled forecast: two weeks from Monday 12/10/2026 with no sales unless a test adds them.
const START = "2026-10-12"
const cheque = (over: Partial<Cheque> = {}): Cheque => ({
  cheque_no: "009901", payee: "Test Supplier", category: "Supplier Payment", amount: 500_000, cheque_date: "2026-10-21",
  expected_clear_date: "2026-10-23", status: "Scheduled", notes: "", ...over,
})
const run = (cheques: Cheque[], opening = 1_000_000, sales: SaleForForecast[] = []) =>
  buildForecast({ openingBalance: opening, start: START, weeks: 2, sales, cheques })
const day = (f: ReturnType<typeof run>, date: string) => f.days.find((d) => d.date === date)!

describe("cheques are deducted on the expected bank clearing date", () => {
  it("deducts on the clearing date (23/10), not the cheque date (21/10)", () => {
    const f = run([cheque()])
    expect(day(f, "2026-10-23").cheque_out).toBe(500_000)
    expect(day(f, "2026-10-21").cheque_out).toBe(0)
  })

  it("deducts the cheque exactly once", () => {
    const f = run([cheque()])
    expect(f.days.reduce((t, d) => t + d.cheque_out, 0)).toBe(500_000)
    expect(f.days.flatMap((d) => d.payments).filter((p) => p.cheque_no === "009901")).toHaveLength(1)
  })

  it("recalculates every later daily balance and leaves earlier days unchanged", () => {
    const base = run([])
    const f = run([cheque()])
    for (const d of f.days) {
      const before = day(base, d.date).closing
      expect(d.closing).toBe(d.date < "2026-10-23" ? before : before - 500_000)
    }
  })

  it("recalculates the weekly summary and carries it into the next week", () => {
    const base = run([])
    const f = run([cheque()])
    expect(f.weeks[1].cheque_out).toBe(500_000) // week of 19–25 Oct
    expect(f.weeks[1].closing).toBe(base.weeks[1].closing - 500_000)
    expect(f.weeks[0].closing).toBe(base.weeks[0].closing)
  })

  it("moves the deduction when the clearing date is edited", () => {
    const f = run([cheque({ expected_clear_date: "2026-10-20" })])
    expect(day(f, "2026-10-20").cheque_out).toBe(500_000)
    expect(day(f, "2026-10-23").cheque_out).toBe(0)
  })

  it("a bounced cheque (recorded outcome) is never deducted and never assumed paid", () => {
    const bounced = cheque({ status: "Bounced", expected_clear_date: "2026-10-09", cheque_date: "2026-10-08" })
    expect(isUpcoming(bounced, "2026-10-10")).toBe(false)
    expect(isAssumedPaid(bounced, "2026-10-10")).toBe(false)
    expect(run([cheque({ status: "Bounced" })]).days.every((d) => d.cheque_out === 0)).toBe(true)
  })

  it("does not deduct cancelled cheques", () => {
    expect(run([cheque({ status: "Cancelled" })]).days.every((d) => d.cheque_out === 0)).toBe(true)
  })

  it("never deducts a cheque twice, even when several cheques clear the same day", () => {
    const f = run([cheque(), cheque({ cheque_no: "009902", amount: 200_000 })])
    expect(day(f, "2026-10-23").cheque_out).toBe(700_000)
    expect(f.days.reduce((t, d) => t + d.cheque_out, 0)).toBe(700_000)
  })
})

describe("cash-shortage warnings follow the cheque", () => {
  it("creates a shortage from the clearing date when the cheque exceeds available cash", () => {
    const f = run([cheque()], 400_000)
    expect(f.shortages).toHaveLength(1)
    expect(f.shortages[0].first_date).toBe("2026-10-23")
    expect(f.shortages[0].shortage_amount).toBe(100_000)
    expect(f.shortages[0].payments.map((p) => p.cheque_no)).toContain("009901")
  })

  it("explains the shortage with the short days only: payments from the first short day, adding up exactly", () => {
    const early = cheque({ cheque_no: "009900", amount: 100_000, cheque_date: "2026-10-19", expected_clear_date: "2026-10-21" })
    const f = run([early, cheque()], 500_000)
    const s = f.shortages[0]
    expect(s.first_date).toBe("2026-10-23")
    expect(s.payments.map((p) => p.cheque_no)).toEqual(["009901"]) // 21/10 cheque is before the shortage, so not listed
    expect(s.payments.every((p) => p.date >= s.first_date && p.date <= s.lowest_date)).toBe(true)
    expect(s.opening_before).toBe(day(f, "2026-10-23").opening)
    expect(s.opening_before + s.receipts_in_window - s.payments_in_window).toBe(s.lowest_balance)
  })

  it("removes the shortage when the cheque is cancelled", () => {
    expect(run([cheque({ status: "Cancelled" })], 400_000).shortages).toHaveLength(0)
  })

  it("moves the shortage when the clearing date moves", () => {
    const f = run([cheque({ expected_clear_date: "2026-10-14", cheque_date: "2026-10-13" })], 400_000)
    expect(f.shortages[0].first_date).toBe("2026-10-14")
  })
})

describe("MVP: every cheque is paid on its expected clearing date", () => {
  it("a cheque clearing after today is upcoming; on or before today it is assumed paid", () => {
    expect(isUpcoming(cheque({ expected_clear_date: "2026-10-11" }), "2026-10-10")).toBe(true)
    expect(isAssumedPaid(cheque({ expected_clear_date: "2026-10-10" }), "2026-10-10")).toBe(true)
    expect(isUpcoming(cheque({ expected_clear_date: "2026-10-10" }), "2026-10-10")).toBe(false)
    expect(isAssumedPaid(cheque({ status: "Cancelled", expected_clear_date: "2026-10-01" }), "2026-10-10")).toBe(false)
  })

  it("does not deduct an assumed-paid cheque again — it is already in the Actual Bank Balance", () => {
    const paid = cheque({ cheque_no: "009800", expected_clear_date: "2026-10-09", cheque_date: "2026-10-08" })
    const f = buildForecast({ openingBalance: 1_000_000, start: START, today: "2026-10-10", weeks: 2, sales: [], cheques: [paid] })
    expect(f.days.every((d) => d.cheque_out === 0)).toBe(true)
    expect(f.days[0].opening).toBe(1_000_000) // the opening (actual) balance is never reduced by a recorded cheque
  })

  it("places a cheque clearing between today and the first forecast day on the first forecast day", () => {
    const f = buildForecast({ openingBalance: 0, start: START, today: "2026-10-10", weeks: 1, sales: [], cheques: [cheque({ expected_clear_date: "2026-10-11", cheque_date: "2026-10-09" })] })
    expect(f.days[0].cheque_out).toBe(500_000)
  })
})

describe("forecast horizon", () => {
  it("covers 12 Monday-to-Sunday weeks by default", () => {
    const f = buildForecast({ openingBalance: 0, start: START, sales: [], cheques: [] })
    expect(FORECAST_WEEKS).toBe(12)
    expect(f.weeks).toHaveLength(12)
    expect(f.days).toHaveLength(84)
    expect(f.days[0].weekday).toBe("Mon")
    expect(f.days.at(-1)).toMatchObject({ date: "2027-01-03", weekday: "Sun" })
  })
})

describe("receipts keep the deposit schedule", () => {
  it("Mon–Thu sales arrive the next morning; Fri–Sun sales on Monday (cash and card)", () => {
    expect(expectedArrivalDate("2026-10-12", "Cash")).toBe("2026-10-13")
    expect(expectedArrivalDate("2026-10-15", "Card")).toBe("2026-10-16")
    for (const d of ["2026-10-16", "2026-10-17", "2026-10-18"]) {
      expect(expectedArrivalDate(d, "Cash")).toBe("2026-10-19")
      expect(expectedArrivalDate(d, "Card")).toBe("2026-10-19")
    }
  })

  it("counts each sale once, on its arrival date", () => {
    const f = run([], 0, [{ date: "2026-10-16", cash_sales: 100, card_sales: 200, actual: false }])
    expect(day(f, "2026-10-19").receipts).toBe(300)
    expect(f.days.reduce((t, d) => t + d.receipts, 0)).toBe(300)
  })
})

describe("sales already in bank data are not expected again", () => {
  // regression: the match key must not follow the display date format (DD/MM/YYYY)
  it("matches bank records like \"Cash sales 05 Oct\" to the sales entered for that date", () => {
    const sale: DailySale = { sales_date: "2026-10-05", cash_sales: 100, card_sales: 200, total: 300, entered_at: "2026-10-05T20:00", entered_by: "X", entry_type: "Standard", note: "" }
    const txn = (record: string, amount: number): BankTransaction => ({
      txn_id: record, txn_date: "2026-10-06", value_date: "2026-10-06", description: "", reference: "", amount, txn_type: "Cash deposit",
      match_status: "Matched", matched_record: record, confidence: 1, note: "", balance_after: 1,
    })
    const r = salesReceipts([sale], [txn("Cash sales 05 Oct", 100), txn("Card sales 05 Oct", 200)], "2026-10-01")
    expect(r.map((x) => x.state)).toEqual(["confirmed", "confirmed"])
  })
})
