import { describe, expect, it } from "vitest"
import type { ApiSalesImport, ApiSalesImportRow } from "@/lib/api/flowledger"
import {
  TEMPLATE_CSV, approvalBlock, coverageDays, defaultSelection, importStatusLabel, invalidReason, rowDateText, rowIssue,
  rowStatusLabel, uploaderLabel,
} from "./sales-import"

const batch = (over: Partial<ApiSalesImport> = {}): ApiSalesImport => ({
  id: "b1", fileName: "history.csv", status: "PENDING_APPROVAL", fileError: null, periodStart: "2025-01-01", periodEnd: "2025-12-31",
  rowCount: 365, validRowCount: 365, invalidRowCount: 0, duplicateRowCount: 0, cashSalesTotal: 1, cardSalesTotal: 2,
  uploadedBy: "u1", uploadedAt: "2026-10-15T10:00:00Z", approvedBy: null, approvedAt: null, approvalNote: null,
  importedRowCount: null, excludedRowCount: null, rejectedBy: null, rejectedAt: null, rejectionReason: null,
  reversedBy: null, reversedAt: null, reversalReason: null, version: 1, ...over,
})

const row = (over: Partial<ApiSalesImportRow> = {}): ApiSalesImportRow => ({
  rowNumber: 2, saleDate: "2025-01-01", cashSalesAmount: 1, cardSalesAmount: 0, status: "VALID", duplicateDate: false,
  errors: [], raw: null, ...over,
})

describe("historical sales import", () => {
  it("offers the exact template header", () => {
    expect(TEMPLATE_CSV.split("\n")[0]).toBe("date,cash_sales,card_payments")
  })

  it("labels statuses for the existing badges", () => {
    expect(importStatusLabel("PENDING_APPROVAL")).toBe("Awaiting approval")
    expect(importStatusLabel("INVALID")).toBe("Invalid")
    expect(rowStatusLabel("INVALID")).toBe("Error")
    expect(rowStatusLabel("DUPLICATE")).toBe("Duplicate")
  })

  it("allows approval only for a Finance Controller and a clean or explicitly trimmed import", () => {
    expect(approvalBlock(batch(), true, false)).toBeNull()
    expect(approvalBlock(batch(), false, false)).toContain("Finance Controller")
    expect(approvalBlock(batch({ status: "APPROVED" }), true, false)).toContain("not waiting")
    expect(approvalBlock(batch({ duplicateRowCount: 2 }), true, true)).toContain("Duplicate")
    expect(approvalBlock(batch({ invalidRowCount: 3 }), true, false)).toContain("left out")
    expect(approvalBlock(batch({ invalidRowCount: 3 }), true, true)).toBeNull()
    expect(approvalBlock(batch({ validRowCount: 0 }), true, true)).toContain("no valid rows")
  })

  it("shows the pending import first", () => {
    expect(defaultSelection([batch({ id: "a", status: "APPROVED" }), batch({ id: "b" })])).toBe("b")
    expect(defaultSelection([batch({ id: "a", status: "REJECTED" })])).toBe("a")
    expect(defaultSelection([])).toBeNull()
  })

  it("explains invalid imports", () => {
    expect(invalidReason(batch({ status: "INVALID", fileError: "The file is empty." }))).toBe("The file is empty.")
    expect(invalidReason(batch({ status: "INVALID", duplicateRowCount: 2 }))).toContain("2 rows")
    expect(invalidReason(batch())).toBeNull()
  })

  it("keeps blank, zero and unreadable cells apart", () => {
    expect(rowDateText(row({ saleDate: null, raw: { date: "2025/01/04" } }))).toBe("2025/01/04")
    expect(rowIssue(row({ errors: [{ field: "date", code: "MISSING", message: "date is missing." }, { field: "cash_sales", code: "NEGATIVE", message: "cash_sales must not be negative." }] })))
      .toBe("date is missing. cash_sales must not be negative.")
    expect(row({ cashSalesAmount: 0 }).cashSalesAmount).toBe(0)
  })

  it("counts the covered days inclusively", () => {
    expect(coverageDays(batch())).toBe(365)
    expect(coverageDays(batch({ periodStart: null, periodEnd: null }))).toBe(0)
  })

  it("names only the current user", () => {
    expect(uploaderLabel("u1", "u1")).toBe("You")
    expect(uploaderLabel("u2", "u1")).toBe("Another user")
  })
})
