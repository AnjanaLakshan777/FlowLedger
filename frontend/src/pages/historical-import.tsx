import { useMemo, useState } from "react"
import { Link } from "react-router-dom"
import { AlertTriangle, Check, CheckCircle2, Download, FileSpreadsheet, Info, Lock, Upload, XCircle } from "lucide-react"
import { toast } from "sonner"
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert"
import { Button } from "@/components/ui/button"
import { Card, CardAction, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card"
import { Checkbox } from "@/components/ui/checkbox"
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog"
import { Label } from "@/components/ui/label"
import { Textarea } from "@/components/ui/textarea"
import { useAppState } from "@/components/app/app-state"
import { CellStack, DataTable } from "@/components/app/data-table"
import { Field } from "@/components/app/field"
import { StatRow } from "@/components/app/kpi-card"
import { PageHeader } from "@/components/app/page-header"
import { Segmented } from "@/components/app/segmented"
import { StatusBadge } from "@/components/app/status-badge"
import { importRows } from "@/data/import-rows"
import type { ImportRecord, ImportRow } from "@/data/types"
import { requiresBackend } from "@/lib/demo"
import { daysBetween, fmt } from "@/lib/format"
import { cn } from "@/lib/utils"

/** Builds the CSV template in the browser — no server needed. */
function downloadTemplate() {
  const csv = "date,cash_sales,card_payments\n2025-01-01,416610.00,509190.00\n"
  const url = URL.createObjectURL(new Blob([csv], { type: "text/csv" }))
  const a = Object.assign(document.createElement("a"), { href: url, download: "flowledger_historical_sales_template.csv" })
  a.click()
  URL.revokeObjectURL(url)
}

export default function HistoricalImportPage() {
  const { imports, actions, allow } = useAppState()
  const current = imports.find((r) => r.import_id === "IMP-0003")!
  const [view, setView] = useState<"issues" | "all">("issues")
  const [skipErrors, setSkipErrors] = useState(false)
  const [approveOpen, setApproveOpen] = useState(false)
  const [rejectOpen, setRejectOpen] = useState(false)
  const [reason, setReason] = useState("")
  const [reasonErr, setReasonErr] = useState<string | null>(null)

  const s = useMemo(() => {
    const dates = importRows.map((r) => r.date).sort()
    return {
      rows: importRows.length,
      valid: importRows.filter((r) => r.status === "Valid").length,
      errors: importRows.filter((r) => r.status === "Error").length,
      warnings: importRows.filter((r) => r.status === "Warning").length,
      duplicates: importRows.filter((r) => r.issue.startsWith("Duplicate date")).length,
      duplicateDates: new Set(importRows.filter((r) => r.issue.startsWith("Duplicate date")).map((r) => r.date)).size,
      first: dates[0], last: dates[dates.length - 1],
    }
  }, [])
  const coverageDays = daysBetween(s.first, s.last) + 1
  const otherErrors = s.errors - s.duplicates
  const shown = view === "issues" ? importRows.filter((r) => r.status !== "Valid") : importRows
  // Accountants and Finance Controllers upload; only a Finance Controller (or System Admin) approves
  const canUpload = allow("import.upload")
  const isFC = allow("import.approve")
  const decided = current.status !== "Awaiting approval"
  // duplicate dates reject the import until resolved; other invalid rows may be left out
  const canApprove = isFC && !decided && s.duplicates === 0 && (otherErrors === 0 || skipErrors)
  const kept = s.valid + s.warnings

  const steps = [
    { n: 1, t: "Upload file", d: "Excel or CSV", state: "done" },
    { n: 2, t: "Validate", d: `${s.errors} invalid · ${s.duplicates} duplicate-date rows`, state: "done" },
    { n: 3, t: "Preview data", d: "Check rows before submitting", state: "done" },
    { n: 4, t: "Finance Controller approval", d: decided ? current.status : "Waiting", state: decided ? "done" : "current" },
  ]

  return (
    <>
      <PageHeader title="Historical Sales Import" description="One-time upload of past daily sales so the forecast has history to learn from." />
      <Alert variant="info">
        <Info />
        <AlertTitle>For initial historical data only — not for daily sales</AlertTitle>
        <AlertDescription>
          <p>New days are entered on the Daily Sales screen. Imported history is used to calculate expected sales; it never changes the Actual Bank Balance.</p>
          <Button variant="outline" size="sm" asChild className="mt-1"><Link to="/daily-sales">Go to Daily Sales</Link></Button>
        </AlertDescription>
      </Alert>

      <ol className="grid gap-2 sm:grid-cols-2 xl:grid-cols-4">
        {steps.map((st) => (
          <li key={st.n} className={cn("bg-card flex min-w-0 items-center gap-2.5 rounded-md border px-3 py-2.5 text-[13px]", st.state === "current" && "border-primary")}>
            <span className={cn("grid size-6 shrink-0 place-items-center rounded-full text-xs font-semibold",
              st.state === "done" ? "bg-primary text-primary-foreground" : "bg-primary/10 text-primary ring-primary ring-1 ring-inset")}>
              {st.state === "done" ? <Check className="size-3.5" /> : st.n}
            </span>
            <div className="min-w-0"><b className="block font-medium">{st.t}</b><span className="text-muted-foreground text-xs">{st.d}</span></div>
          </li>
        ))}
      </ol>

      <div className="grid items-start gap-4 xl:grid-cols-[380px_minmax(0,1fr)]">
        <div className="grid gap-4">
          <Card>
            <CardHeader><CardTitle>Upload</CardTitle><CardDescription>One file covering the full historical period — ideally at least one year</CardDescription></CardHeader>
            <CardContent className="grid gap-3">
              <div className="flex items-center gap-3 rounded-md border p-3">
                <div className="bg-primary/10 text-primary grid size-9 shrink-0 place-items-center rounded-md"><FileSpreadsheet className="size-[18px]" /></div>
                <CellStack className="flex-1" main={<b className="font-semibold break-all">{current.file_name}</b>} sub={`${fmt.int(current.rows)} rows · uploaded ${fmt.dateTime(current.uploaded_at)} by ${current.uploaded_by}`} />
                <StatusBadge status={current.status} />
              </div>
              <label className="border-ring bg-muted text-muted-foreground grid cursor-pointer justify-items-center gap-2 rounded-lg border border-dashed p-5 text-center text-[13px]">
                <Upload className="size-5" />
                <span><b className="text-foreground">Replace file</b> — drag an .xlsx, .xls or .csv here</span>
                <span className="text-xs">Max 10 MB · one row per day</span>
                <input type="file" accept=".xlsx,.xls,.csv" className="sr-only" disabled={!canUpload}
                  onChange={(e) => e.target.files?.[0] && requiresBackend("Validating " + e.target.files[0].name, "The file stayed in your browser. The results below are demonstration data.")} />
                <span className="border-input bg-background text-foreground mt-1 inline-flex h-8 items-center rounded-md border px-3 text-sm font-medium shadow-xs">Choose file</span>
              </label>
            </CardContent>
          </Card>
          <Card>
            <CardHeader>
              <CardTitle>Required columns</CardTitle>
              <CardDescription>Column names must match exactly (first row)</CardDescription>
              <CardAction><Button variant="outline" size="sm" onClick={downloadTemplate}><Download />Template</Button></CardAction>
            </CardHeader>
            <CardContent>
              <div className="overflow-hidden rounded-md border text-[13px]">
                <div className="bg-muted text-muted-foreground grid grid-cols-[120px_1fr] gap-3 px-3 py-2 text-xs font-medium"><span>Column</span><span>Format</span></div>
                {[["date", "YYYY-MM-DD · one row per date, no duplicates"], ["cash_sales", "Number ≥ 0, LKR, no currency symbol"], ["card_payments", "Number ≥ 0, LKR, no currency symbol"]].map(([c, d]) => (
                  <div key={c} className="grid grid-cols-[120px_1fr] gap-3 border-t px-3 py-2"><span className="font-mono text-xs">{c}</span><span>{d}</span></div>
                ))}
              </div>
            </CardContent>
          </Card>
        </div>

        <div className="grid gap-4">
          <Card>
            <CardHeader><CardTitle>Validation results</CardTitle><CardDescription>{current.file_name} · {fmt.date(s.first)} to {fmt.date(s.last)}</CardDescription></CardHeader>
            <CardContent>
              <StatRow items={[
                { label: "Rows", value: fmt.int(s.rows) },
                { label: "Valid", value: fmt.int(s.valid) },
                { label: "Invalid rows", value: s.errors, negative: s.errors > 0 },
                { label: "Warnings", value: s.warnings },
                { label: "Duplicate dates", value: s.duplicateDates, sub: `${s.duplicates} rows`, negative: s.duplicates > 0 },
                { label: "Coverage", value: `${(coverageDays / 365).toFixed(1)} yrs`, sub: coverageDays >= 365 ? "At least 1 year" : "Less than 1 year", negative: coverageDays < 365 },
              ]} />
            </CardContent>
          </Card>
          <Card>
            <CardHeader>
              <CardTitle>Finance Controller approval</CardTitle>
              <CardDescription>Only a Finance Controller can approve. Records don't affect the forecast until then.</CardDescription>
            </CardHeader>
            <CardContent className="grid gap-3">
              {current.status === "Approved" ? (
                <Alert variant="info"><CheckCircle2 /><AlertTitle>Approved by {current.approved_by}</AlertTitle><AlertDescription>{fmt.dateTimeFull(current.approved_at)}. These records are now used for forecasting.</AlertDescription></Alert>
              ) : current.status === "Rejected" ? (
                <Alert><XCircle /><AlertTitle>Rejected by {current.approved_by}</AlertTitle><AlertDescription>{fmt.dateTimeFull(current.approved_at)}. Upload a corrected file to try again.</AlertDescription></Alert>
              ) : (
                <>
                  {s.duplicates > 0 && (
                    <Alert variant="destructive"><AlertTriangle /><AlertTitle>Rejected until duplicate dates are resolved</AlertTitle>
                      <AlertDescription>{s.duplicateDates} dates appear more than once ({s.duplicates} rows). Correct the file and upload it again.</AlertDescription></Alert>
                  )}
                  {otherErrors > 0 && (
                    <div className="flex items-center gap-2">
                      <Checkbox id="skip" checked={skipErrors} onCheckedChange={(v) => setSkipErrors(v === true)} disabled={!isFC || s.duplicates > 0} />
                      <Label htmlFor="skip" className="font-normal">Leave out the {otherErrors} other invalid rows</Label>
                    </div>
                  )}
                  {!isFC && <p className="text-muted-foreground flex items-center gap-1.5 text-xs"><Lock className="size-3" />Waiting for a Finance Controller to approve.</p>}
                  <div className="flex gap-2">
                    <Button disabled={!canApprove} onClick={() => setApproveOpen(true)}><Check />Approve import</Button>
                    <Button variant="outline" disabled={!isFC} onClick={() => setRejectOpen(true)}>Reject</Button>
                  </div>
                </>
              )}
            </CardContent>
          </Card>
          <Card>
            <CardHeader><CardTitle>Data preview</CardTitle><CardDescription>Invalid rows and their reasons, or every row in the file</CardDescription></CardHeader>
            <CardContent>
              <Segmented value={view} onChange={setView} options={[{ value: "issues", label: "Issues", count: s.errors + s.warnings }, { value: "all", label: "All rows", count: fmt.int(s.rows) }]} />
            </CardContent>
            <DataTable<ImportRow>
              rows={shown}
              rowKey={(r) => String(r.row)}
              defaultSort={{ key: "row", dir: "asc" }}
              maxHeight={420}
              footer={(rs) => <><span>{fmt.int(rs.length)} rows shown</span><span>Values in LKR</span></>}
              columns={[
                { key: "row", header: "Row", numeric: true, sortValue: (r) => r.row, cell: (r) => <span className="text-muted-foreground">{r.row}</span> },
                { key: "date", header: "date", sortValue: (r) => r.date, cell: (r) => <span className="font-mono text-xs" title={r.date}>{/^\d{4}-\d{2}-\d{2}$/.test(r.date) ? fmt.date(r.date) : r.date}</span> },
                { key: "cash", header: "cash_sales", numeric: true, sortValue: (r) => r.cash_sales ?? -1, cell: (r) => (r.cash_sales == null ? <span className="text-destructive">empty</span> : fmt.amt(r.cash_sales)) },
                { key: "card", header: "card_payments", numeric: true, sortValue: (r) => r.card_payments, cell: (r) => <span className={cn(r.card_payments < 0 && "text-destructive")}>{fmt.amt(r.card_payments)}</span> },
                { key: "status", header: "Check", sortValue: (r) => r.status, cell: (r) => <StatusBadge status={r.status} /> },
                { key: "issue", header: "Issue", cell: (r) => <span className={r.status === "Error" ? "text-destructive" : "text-muted-foreground"}>{r.issue}</span> },
              ]}
            />
          </Card>
        </div>
      </div>

      <Card>
        <CardHeader><CardTitle>Import history</CardTitle><CardDescription>Initial historical imports only, with who uploaded and who approved each one.</CardDescription></CardHeader>
        <DataTable<ImportRecord>
          rows={imports}
          rowKey={(r) => r.import_id}
          maxHeight="none"
          columns={[
            { key: "id", header: "Import", cell: (r) => <span className="font-mono text-xs">{r.import_id}</span> },
            { key: "file", header: "File", cell: (r) => r.file_name },
            { key: "period", header: "Period", cell: (r) => r.period },
            { key: "rows", header: "Rows", numeric: true, cell: (r) => fmt.int(r.rows) },
            { key: "uploaded", header: "Uploaded", cell: (r) => <CellStack main={fmt.dateTime(r.uploaded_at)} sub={r.uploaded_by} /> },
            { key: "status", header: "Status", cell: (r) => <StatusBadge status={r.status} /> },
            { key: "by", header: "Approved / rejected", cell: (r) => (r.approved_by ? <CellStack main={r.approved_by} sub={fmt.dateTime(r.approved_at)} /> : "—") },
          ]}
        />
      </Card>

      <Dialog open={approveOpen} onOpenChange={setApproveOpen}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>Approve historical import?</DialogTitle>
            <DialogDescription>{fmt.int(kept)} rows from {fmt.date(s.first)} to {fmt.date(s.last)} will be used for forecasting.{otherErrors > 0 && ` ${otherErrors} invalid rows will be left out.`}</DialogDescription>
          </DialogHeader>
          <Field label="Approval note (optional)" htmlFor="appr-note"><Textarea id="appr-note" placeholder="e.g. Checked totals against audited 2023–2025 accounts" /></Field>
          <DialogFooter>
            <Button variant="outline" onClick={() => setApproveOpen(false)}>Cancel</Button>
            <Button onClick={() => { actions.setImportStatus(current.import_id, "Approved"); setApproveOpen(false); toast.success("Historical import approved", { description: `${fmt.int(kept)} rows · demonstration only, nothing was saved` }) }}>Approve and import</Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      <Dialog open={rejectOpen} onOpenChange={setRejectOpen}>
        <DialogContent>
          <DialogHeader><DialogTitle>Reject this import?</DialogTitle><DialogDescription>The file will not be imported.</DialogDescription></DialogHeader>
          <Field label="Reason for rejection" htmlFor="rej" error={reasonErr}>
            <Textarea id="rej" value={reason} onChange={(e) => setReason(e.target.value)} placeholder="Tell the uploader what to fix" aria-invalid={!!reasonErr || undefined} />
          </Field>
          <DialogFooter>
            <Button variant="outline" onClick={() => setRejectOpen(false)}>Cancel</Button>
            <Button variant="destructive" onClick={() => {
              if (!reason.trim()) return setReasonErr("A reason is required.")
              actions.setImportStatus(current.import_id, "Rejected"); setRejectOpen(false); toast("Import rejected")
            }}>Reject import</Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </>
  )
}
