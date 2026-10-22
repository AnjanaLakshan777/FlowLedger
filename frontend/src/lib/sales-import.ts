import type { ApiSalesImport, ApiSalesImportRow, SalesImportStatus } from "@/lib/api/flowledger"
import { daysBetween } from "@/lib/format"

/** The standard template: the exact header and one example row (a past date, LKR, no symbols). */
export const TEMPLATE_CSV = "date,cash_sales,card_payments\n2025-01-01,416610.00,509190.00\n"
export const TEMPLATE_FILE_NAME = "flowledger_historical_sales_template.csv"

/** What the server accepts, shown next to the upload box. */
export const UPLOAD_LIMITS = "CSV (UTF-8) only · max 1 MB · up to 5,000 rows · one row per date"

export const TEMPLATE_COLUMNS: [string, string][] = [
  ["date", "YYYY-MM-DD · a past or today's date · one row per date, no duplicates"],
  ["cash_sales", "Number ≥ 0 in LKR, at most 2 decimals, no symbols or separators · 0 for none, never blank"],
  ["card_payments", "Number ≥ 0 in LKR, at most 2 decimals, no symbols or separators · 0 for none, never blank"],
]

const STATUS_LABEL: Record<SalesImportStatus, string> = {
  PENDING_APPROVAL: "Awaiting approval", APPROVED: "Approved", REJECTED: "Rejected", INVALID: "Invalid", REVERSED: "Reversed",
}
export const importStatusLabel = (s: SalesImportStatus) => STATUS_LABEL[s] ?? s

const ROW_LABEL: Record<ApiSalesImportRow["status"], string> = { VALID: "Valid", INVALID: "Error", DUPLICATE: "Duplicate" }
export const rowStatusLabel = (s: ApiSalesImportRow["status"]) => ROW_LABEL[s] ?? s

/** All problems of a row in one line, in the order found. */
export const rowIssue = (row: ApiSalesImportRow) => row.errors.map((e) => e.message).join(" ")

/** The date cell as written for rows that could not be read, otherwise the parsed date. */
export const rowDateText = (row: ApiSalesImportRow) => row.saleDate ?? row.raw?.date ?? ""

/** Days from the first to the last valid date, inclusive; 0 when there is no valid row. */
export function coverageDays(batch: Pick<ApiSalesImport, "periodStart" | "periodEnd">) {
  return batch.periodStart && batch.periodEnd ? daysBetween(batch.periodStart, batch.periodEnd) + 1 : 0
}

export const uploaderLabel = (userId: string | null, currentUserId: string | null) =>
  !userId ? "—" : userId === currentUserId ? "You" : "Another user"

/**
 * Whether the approve button may be used now, and why not. The server checks all of this again;
 * the screen only avoids offering an action that would be refused.
 */
export function approvalBlock(batch: ApiSalesImport, canApprove: boolean, leaveOutInvalidRows: boolean): string | null {
  if (batch.status !== "PENDING_APPROVAL") return "This import is not waiting for approval."
  if (!canApprove) return "Waiting for a Finance Controller to approve."
  if (batch.duplicateRowCount > 0) return "Duplicate dates must be resolved first."
  if (batch.validRowCount === 0) return "The import has no valid rows."
  if (batch.invalidRowCount > 0 && !leaveOutInvalidRows) return "Confirm that the invalid rows are left out."
  return null
}

/** The import to show first: the newest one waiting for approval, otherwise the newest. */
export function defaultSelection(imports: ApiSalesImport[]): string | null {
  return (imports.find((i) => i.status === "PENDING_APPROVAL") ?? imports[0])?.id ?? null
}

/** A short explanation of an invalid import, from its file error or its rows. */
export function invalidReason(batch: ApiSalesImport): string | null {
  if (batch.status !== "INVALID") return null
  if (batch.fileError) return batch.fileError
  if (batch.duplicateRowCount > 0) return `${batch.duplicateRowCount} rows have a duplicate date or a date that already has recorded sales. Correct the file and upload it again.`
  if (batch.validRowCount === 0) return "No row is valid. Correct the file and upload it again."
  return null
}
