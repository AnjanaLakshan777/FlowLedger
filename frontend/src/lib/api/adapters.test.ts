import { describe, expect, it } from "vitest"
import { colomboLocal, colomboToday, forecastFromRun, forecastVsActualWeeks, toBankAccount, toCheque, toChequeRequest, toDailySale, toTransaction } from "./adapters"
import type { ApiBankAccount, ForecastRun, ForecastRunDay, ForecastVsActual } from "./flowledger"

// Shapes as the services return them (taken from the end-to-end run; fictional test data).
const day = (date: string, over: Partial<ForecastRunDay> = {}): ForecastRunDay => ({
  date, expectedCashSalesDeposits: 0, expectedCardSalesSettlements: 0, expectedSalesInflows: 0, expectedChequeOutflows: 0,
  expectedChequeCount: 0, openingBalance: 100, closingBalance: 100, cashShortage: false, ...over,
})
const dates = (start: string, n: number) => Array.from({ length: n }, (_, i) => new Date(Date.parse(start + "T00:00:00Z") + i * 864e5).toISOString().slice(0, 10))

function run(over: Partial<ForecastRun["run"]> = {}, days?: ForecastRunDay[]): ForecastRun {
  const ds = days ?? dates("2026-10-01", 84).map((d) => day(d))
  return {
    run: {
      id: "run-1", bankAccountId: "acc", runStatus: "SUCCEEDED", asOfAt: "2026-10-15T04:30:00Z", horizonStartDate: ds[0].date, horizonEndDate: ds.at(-1)!.date,
      openingBalance: 100, openingBalanceAsOf: "2026-09-30T18:29:59Z", currency: "LKR", balanceStale: false, inflowEstimateStatus: "ESTIMATED",
      dataQuality: { historyFrom: "2026-08-06", historyTo: "2026-09-30", balanceAgeDays: 1, balanceStaleAfterDays: 3, unclassifiedCreditCount: 0 },
      warnings: [], createdAt: "2026-10-15T04:30:00Z", ...over,
    },
    totals: { expectedSalesInflows: 0, expectedChequeOutflows: 0, closingBalance: 100, lowestClosingBalance: 100, lowestBalanceDate: ds[0].date, firstShortageDate: null, shortageDays: 0 },
    days: ds, cheques: [],
  }
}

describe("forecast runs", () => {
  it("keeps the backend's dates: the forecast starts on the returned horizon start, not on a Monday", () => {
    const f = forecastFromRun(run(), [])
    expect(f.days).toHaveLength(84)
    expect(f.days[0]).toMatchObject({ date: "2026-10-01", weekday: "Thu", week: 1 })
    expect(f.weeks).toHaveLength(12)
    expect(f.weeks[0]).toMatchObject({ week_start: "2026-10-01", week_end: "2026-10-07" })
    expect(f.weeks[11].week_end).toBe("2026-12-23")
  })

  it("invents no likely range: low and high are the forecast closing balance", () => {
    const f = forecastFromRun(run({}, dates("2026-10-01", 84).map((d, i) => day(d, { closingBalance: 100 - i }))), [])
    expect(f.days.every((d) => d.low === d.closing && d.high === d.closing)).toBe(true)
  })

  it("marks receipts as not estimated when history was insufficient", () => {
    const days = dates("2026-10-01", 84).map((d) => day(d, { expectedCashSalesDeposits: null, expectedCardSalesSettlements: null, expectedSalesInflows: null }))
    const f = forecastFromRun(run({ inflowEstimateStatus: "INSUFFICIENT_HISTORY" }, days), [])
    expect(f.days.every((d) => d.inflows_estimated === false && d.arrivals.length === 0)).toBe(true)
  })

  it("places cheques of the run on their clearing date, with payees from the payment service", () => {
    const days = dates("2026-10-01", 84).map((d) => day(d, d === "2026-10-20" ? { expectedChequeOutflows: 20000, expectedChequeCount: 1, closingBalance: -19900, cashShortage: true } : {}))
    const r = { ...run({}, days), cheques: [{ chequeId: "c1", chequeNumber: "100001", amount: 20000, expectedClearingDate: "2026-10-20" }] }
    const cheques = [toCheque({ id: "c1", bankAccountId: "acc", chequeNumber: "100001", payeeName: "E2E Supplier", category: "SUPPLIER_PAYMENT", otherDescription: null, amount: 20000,
      issueDate: "2026-10-15", expectedClearingDate: "2026-10-20", status: "ISSUED", notes: null, version: 1 })]
    const f = forecastFromRun(r, cheques)
    const d20 = f.days.find((d) => d.date === "2026-10-20")!
    expect(d20.payments).toEqual([{ cheque_no: "100001", payee: "E2E Supplier", category: "Supplier Payment", amount: 20000, date: "2026-10-20" }])
    expect(f.shortages[0]).toMatchObject({ first_date: "2026-10-20", shortage_amount: 19900 })
  })
})

describe("records", () => {
  it("never turns a missing confirmed balance into zero", () => {
    const account: ApiBankAccount = { id: "acc", bankName: "Commercial Bank of Ceylon", branchName: "Colombo 03", accountName: "E2E", accountType: "CURRENT", accountNumberLast4: "4821", currency: "LKR", integrationMode: "STATEMENT_IMPORT", status: "ACTIVE" }
    expect(toBankAccount(account, null, []).balance).toBeNull()
    expect(toBankAccount(account, { bankAccountId: "acc", balanceAmount: 8373.55, currency: "LKR", balanceAsOf: "2026-09-30T18:29:59Z", sourceType: "STATEMENT_IMPORT" }, []))
      .toMatchObject({ balance: 8373.55, balance_as_of: "2026-09-30T23:59", account_masked: "•••• 4821", branch: "Colombo 03" })
  })

  it("maps cheque statuses without a paid status", () => {
    const base = { id: "c", bankAccountId: "a", chequeNumber: "000123", payeeName: "P", category: "TAX" as const, otherDescription: null, amount: 1, issueDate: "2026-10-01", expectedClearingDate: "2026-10-02", notes: null, version: 2 }
    expect(toCheque({ ...base, status: "ISSUED" })).toMatchObject({ status: "Scheduled", cheque_no: "000123", category: "Tax", version: 2 })
    expect(toCheque({ ...base, status: "CANCELLED" }).status).toBe("Cancelled")
  })

  it("sends an Other Expenses description and drops it for other categories", () => {
    const base = { id: "c", bankAccountId: "a", chequeNumber: "000123", payeeName: "P", amount: 1, issueDate: "2026-10-01", expectedClearingDate: "2026-10-02", status: "ISSUED" as const, notes: null, version: 2 }
    const other = toCheque({ ...base, category: "OTHER_EXPENSES", otherDescription: "Audit fee" })
    expect(other).toMatchObject({ category: "Other Expenses", other_description: "Audit fee" })
    expect(toChequeRequest(other).otherDescription).toBe("Audit fee")
    expect(toChequeRequest({ ...other, category: "Tax" }).otherDescription).toBeNull()
  })

  it("signs bank transactions and keeps their category", () => {
    const t = toTransaction({ id: "t", bankAccountId: "a", transactionDate: "2026-10-03", direction: "DEBIT", amount: 1500, currency: "LKR", description: "CHEQUE PAYMENT",
      runningBalance: 9373.55, chequeNumber: "000777", category: "CHEQUE_PAYMENT", otherDescription: null, classificationMethod: "MANUAL", entrySource: "STATEMENT_IMPORT", sourceImportBatchId: "b" })
    expect(t).toMatchObject({ amount: -1500, txn_type: "Cheque", balance_after: 9373.55, reference: "Cheque payment · Cheque 000777" })
  })

  it("shows sales entry times in Colombo and marks late entries as backdated", () => {
    const s = toDailySale({ id: "s", companyId: "c", saleDate: "2026-10-10", cashSalesAmount: 1, cardSalesAmount: 2, totalSalesAmount: 3, entrySource: "MANUAL",
      recordedBy: "u1", recordedAt: "2026-10-14T04:00:00Z" }, "u1")
    expect(s).toMatchObject({ entered_at: "2026-10-14T09:30", entered_by: "You", entry_type: "Backdated" })
  })

  it("marks sales from an approved historical import as imported, not backdated", () => {
    const s = toDailySale({ id: "s", companyId: "c", saleDate: "2025-01-01", cashSalesAmount: 1, cardSalesAmount: 2, totalSalesAmount: 3,
      entrySource: "HISTORICAL_IMPORT", recordedBy: "u2", recordedAt: "2026-10-14T04:00:00Z" }, "u1")
    expect(s).toMatchObject({ entry_type: "Imported", entered_by: "Another user", total: 3 })
  })

  it("uses the Asia/Colombo date", () => {
    expect(colomboToday(new Date("2026-10-14T19:00:00Z"))).toBe("2026-10-15")
    expect(colomboLocal("2026-10-14T18:29:59Z")).toBe("2026-10-14T23:59")
  })
})

describe("forecast vs actual", () => {
  const fva: ForecastVsActual = {
    forecastRunId: "run-1", horizonStartDate: "2026-10-01", horizonEndDate: "2026-12-23", inflowEstimateStatus: "ESTIMATED", actualsThrough: "2026-10-31", comparedThrough: "2026-10-05",
    warnings: [], totals: { daysCompared: 5, unclassifiedTransactionCount: 1, unclassifiedTransactionAmount: 100 },
    days: dates("2026-10-01", 84).map((d, i) => ({
      date: d, actualsAvailable: i < 5, forecastSalesInflows: 100, actualSalesInflows: i < 5 ? 150 : null, forecastChequeOutflows: i === 2 ? 1500 : 0,
      actualChequeOutflows: i < 5 ? (i === 2 ? 1500 : 0) : null, forecastClosingBalance: 1000 + i, actualClosingBalance: i < 5 ? 1100 + i : null, unclassifiedTransactions: 0,
    })),
  }

  it("compares only days with bank-confirmed actuals, in 7-day periods from the forecast start", () => {
    const rows = forecastVsActualWeeks(fva)
    expect(rows).toHaveLength(1)
    expect(rows[0]).toMatchObject({
      week_start: "2026-10-01", week_end: "2026-10-07", days_compared: 5, forecast_in: 500, actual_in: 750, diff_in: 250,
      forecast_out: 1500, actual_out: 1500, diff_out: 0, forecast_closing: 1004, actual_closing: 1104, diff_closing: 100,
    })
  })

  it("keeps receipts unknown when the forecast did not estimate them", () => {
    const rows = forecastVsActualWeeks({ ...fva, days: fva.days.map((d) => ({ ...d, forecastSalesInflows: null })) })
    expect(rows[0]).toMatchObject({ forecast_in: null, diff_in: null, actual_in: 750 })
  })
})
