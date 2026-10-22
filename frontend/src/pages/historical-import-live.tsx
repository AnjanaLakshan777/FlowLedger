import { useCallback, useEffect, useMemo, useRef, useState } from "react"
import { Link } from "react-router-dom"
import { AlertTriangle, Check, CheckCircle2, Download, FileSpreadsheet, Info, Loader2, Lock, RefreshCw, Undo2, Upload, XCircle } from "lucide-react"
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
import { LoadingCard } from "@/components/app/data-state"
import { Field } from "@/components/app/field"
import { StatRow } from "@/components/app/kpi-card"
import { PageHeader } from "@/components/app/page-header"
import { Segmented } from "@/components/app/segmented"
import { StatusBadge } from "@/components/app/status-badge"
import { colomboLocal } from "@/lib/api/adapters"
import { ApiError, errorMessage } from "@/lib/api/client"
import { flowledger, type ApiSalesImport, type ApiSalesImportDetail, type ApiSalesImportOverview, type ApiSalesImportRow } from "@/lib/api/flowledger"
import { fmt } from "@/lib/format"
import {
  TEMPLATE_COLUMNS, TEMPLATE_CSV, TEMPLATE_FILE_NAME, UPLOAD_LIMITS, approvalBlock, coverageDays, defaultSelection, importStatusLabel,
  invalidReason, rowDateText, rowIssue, rowStatusLabel, uploaderLabel,
} from "@/lib/sales-import"
import { cn } from "@/lib/utils"

/** Builds the CSV template in the browser — no server needed. */
function downloadTemplate() {
  const url = URL.createObjectURL(new Blob([TEMPLATE_CSV], { type: "text/csv" }))
  const a = Object.assign(document.createElement("a"), { href: url, download: TEMPLATE_FILE_NAME })
  a.click()
  URL.revokeObjectURL(url)
}

const local = (instant: string | null) => (instant ? colomboLocal(instant) : null)

/**
 * The Historical Sales Import screen with company data from cash-management-service. Validation,
 * duplicate checks, permissions and the approval itself happen on the server; this screen shows
 * the results and only offers the actions the server reports as allowed.
 */
export default function LiveHistoricalImportPage() {
  const { company, currentUser, reload } = useAppState()
  const cid = company?.id ?? null
  const me = currentUser?.user_id ?? null

  const [overview, setOverview] = useState<ApiSalesImportOverview | null>(null)
  const [overviewError, setOverviewError] = useState<string | null>(null)
  const [selectedId, setSelectedId] = useState<string | null>(null)
  const [detail, setDetail] = useState<ApiSalesImportDetail | null>(null)
  const [detailError, setDetailError] = useState<string | null>(null)
  const [uploading, setUploading] = useState(false)
  const [busy, setBusy] = useState(false)
  const [view, setView] = useState<"issues" | "all">("issues")
  const [leaveOut, setLeaveOut] = useState(false)
  const [approveOpen, setApproveOpen] = useState(false)
  const [rejectOpen, setRejectOpen] = useState(false)
  const [reverseOpen, setReverseOpen] = useState(false)
  const [note, setNote] = useState("")
  const [reason, setReason] = useState("")
  const [reasonErr, setReasonErr] = useState<string | null>(null)
  const generation = useRef(0)

  const loadOverview = useCallback(async (select?: string) => {
    if (!cid) return
    const g = ++generation.current
    setOverviewError(null)
    try {
      const next = await flowledger.salesImports(cid)
      if (g !== generation.current) return
      setOverview(next)
      setSelectedId((current) => select ?? (current && next.imports.some((i) => i.id === current) ? current : defaultSelection(next.imports)))
    } catch (e) {
      if (g === generation.current) setOverviewError(errorMessage(e))
    }
  }, [cid])

  useEffect(() => {
    setOverview(null)
    setSelectedId(null)
    setDetail(null)
    void loadOverview()
  }, [loadOverview])

  useEffect(() => {
    if (!cid || !selectedId) { setDetail(null); return }
    let current = true
    setDetailError(null)
    setLeaveOut(false)
    flowledger.salesImport(cid, selectedId)
      .then((d) => { if (current) setDetail(d) })
      .catch((e) => { if (current) setDetailError(errorMessage(e)) })
    return () => { current = false }
  }, [cid, selectedId])

  const rows = detail?.rows ?? []
  const batch = detail?.batch.id === selectedId ? detail.batch : null
  const issues = useMemo(() => rows.filter((r) => r.status !== "VALID"), [rows])
  const duplicateDates = useMemo(() => new Set(rows.filter((r) => r.status === "DUPLICATE").map((r) => rowDateText(r))).size, [rows])

  async function upload(file: File) {
    if (!cid) return
    setUploading(true)
    try {
      const result = await flowledger.uploadSalesImport(cid, file)
      const b = result.batch
      if (b.status === "INVALID") toast.error("File checked: it cannot be approved", { description: invalidReason(b) ?? undefined })
      else toast.success("File checked and waiting for approval", { description: `${fmt.int(b.validRowCount)} valid rows · ${fmt.int(b.invalidRowCount)} invalid rows` })
      setDetail(result)
      await loadOverview(b.id)
    } catch (e) {
      toast.error("The file was not uploaded", { description: errorMessage(e) })
    } finally {
      setUploading(false)
    }
  }

  async function decide(action: "approve" | "reject" | "reverse") {
    if (!cid || !batch) return
    if (action !== "approve" && !reason.trim()) return setReasonErr("A reason is required.")
    setBusy(true)
    try {
      const result = action === "approve"
        ? await flowledger.approveSalesImport(cid, batch.id, batch.version, leaveOut, note.trim() || null)
        : action === "reject"
          ? await flowledger.rejectSalesImport(cid, batch.id, batch.version, reason.trim())
          : await flowledger.reverseSalesImport(cid, batch.id, batch.version, reason.trim())
      setDetail(result)
      setApproveOpen(false); setRejectOpen(false); setReverseOpen(false); setNote(""); setReason(""); setReasonErr(null)
      if (action === "approve") toast.success("Historical import approved", { description: `${fmt.int(result.batch.importedRowCount ?? 0)} rows recorded as daily sales` })
      else if (action === "reject") toast("Import rejected")
      else toast("Import reversed", { description: "Its daily sales were deleted. Upload a corrected file for these dates." })
      await loadOverview(batch.id)
      if (action !== "reject") reload("sales")
    } catch (e) {
      const conflict = e instanceof ApiError && e.status === 409
      const verb = action === "approve" ? "approved" : action === "reject" ? "rejected" : "reversed"
      toast.error(`The import was not ${verb}`, { description: errorMessage(e) })
      if (conflict) { setApproveOpen(false); setRejectOpen(false); setReverseOpen(false); await loadOverview(batch.id) }
    } finally {
      setBusy(false)
    }
  }

  const canUpload = overview?.canUpload ?? false
  const canApprove = overview?.canApprove ?? false
  const block = batch ? approvalBlock(batch, canApprove, leaveOut) : null
  const coverage = batch ? coverageDays(batch) : 0

  const steps = [
    { n: 1, t: "Upload file", d: "CSV", state: batch ? "done" : "current" },
    { n: 2, t: "Validate", d: batch ? `${batch.invalidRowCount} invalid · ${batch.duplicateRowCount} duplicate-date rows` : "Checked on upload", state: batch ? "done" : "todo" },
    { n: 3, t: "Preview data", d: "Check rows before approval", state: batch ? "done" : "todo" },
    { n: 4, t: "Finance Controller approval", d: batch ? importStatusLabel(batch.status) : "Waiting", state: batch && batch.status !== "PENDING_APPROVAL" ? "done" : batch ? "current" : "todo" },
  ]

  return (
    <>
      <PageHeader title="Historical Sales Import" description="One-time upload of past daily sales so the forecast has history to learn from." />
      <Alert variant="info">
        <Info />
        <AlertTitle>For initial historical data only — not for daily sales</AlertTitle>
        <AlertDescription>
          <p>New days are entered on the Daily Sales screen. Imported sales are sales records only: they are never bank inflows and never change the Actual Bank Balance.</p>
          <Button variant="outline" size="sm" asChild className="mt-1"><Link to="/daily-sales">Go to Daily Sales</Link></Button>
        </AlertDescription>
      </Alert>

      {overviewError && (
        <Alert variant="destructive">
          <AlertTriangle />
          <AlertTitle>Imports could not be loaded</AlertTitle>
          <AlertDescription>
            <p>{overviewError}</p>
            <Button variant="outline" size="sm" className="mt-1" onClick={() => void loadOverview()}><RefreshCw />Try again</Button>
          </AlertDescription>
        </Alert>
      )}
      {!overview && !overviewError && <LoadingCard>Loading imports…</LoadingCard>}

      {overview && (
        <>
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
                  {batch && (
                    <div className="flex items-center gap-3 rounded-md border p-3">
                      <div className="bg-primary/10 text-primary grid size-9 shrink-0 place-items-center rounded-md"><FileSpreadsheet className="size-[18px]" /></div>
                      <CellStack className="flex-1" main={<b className="font-semibold break-all">{batch.fileName}</b>}
                        sub={`${fmt.int(batch.rowCount)} rows · uploaded ${fmt.dateTime(local(batch.uploadedAt))} by ${uploaderLabel(batch.uploadedBy, me)}`} />
                      <StatusBadge status={importStatusLabel(batch.status)} />
                    </div>
                  )}
                  {canUpload ? (
                    <label className={cn("border-ring bg-muted text-muted-foreground grid cursor-pointer justify-items-center gap-2 rounded-lg border border-dashed p-5 text-center text-[13px]", uploading && "pointer-events-none opacity-60")}>
                      {uploading ? <Loader2 className="size-5 animate-spin" /> : <Upload className="size-5" />}
                      <span><b className="text-foreground">{uploading ? "Checking the file…" : batch ? "Upload another file" : "Upload a file"}</b> — a .csv file</span>
                      <span className="text-xs">{UPLOAD_LIMITS}</span>
                      <input type="file" accept=".csv,text/csv" className="sr-only" disabled={uploading}
                        onChange={(e) => { const f = e.target.files?.[0]; e.target.value = ""; if (f) void upload(f) }} />
                      <span className="border-input bg-background text-foreground mt-1 inline-flex h-8 items-center rounded-md border px-3 text-sm font-medium shadow-xs">Choose file</span>
                    </label>
                  ) : (
                    <p className="text-muted-foreground flex items-center gap-1.5 text-xs"><Lock className="size-3" />Your role can review imports but not upload them.</p>
                  )}
                </CardContent>
              </Card>
              <Card>
                <CardHeader>
                  <CardTitle>Required columns</CardTitle>
                  <CardDescription>Column names must match exactly (first row). Save from Excel as CSV UTF-8.</CardDescription>
                  <CardAction><Button variant="outline" size="sm" onClick={downloadTemplate}><Download />Template</Button></CardAction>
                </CardHeader>
                <CardContent>
                  <div className="overflow-hidden rounded-md border text-[13px]">
                    <div className="bg-muted text-muted-foreground grid grid-cols-[120px_1fr] gap-3 px-3 py-2 text-xs font-medium"><span>Column</span><span>Format</span></div>
                    {TEMPLATE_COLUMNS.map(([c, d]) => (
                      <div key={c} className="grid grid-cols-[120px_1fr] gap-3 border-t px-3 py-2"><span className="font-mono text-xs">{c}</span><span>{d}</span></div>
                    ))}
                  </div>
                </CardContent>
              </Card>
            </div>

            <div className="grid gap-4">
              {!selectedId ? (
                <Card><CardContent className="text-muted-foreground py-10 text-center text-sm">No historical imports yet. {canUpload ? "Download the template, fill it in and upload it." : ""}</CardContent></Card>
              ) : detailError ? (
                <Alert variant="destructive"><AlertTriangle /><AlertTitle>The import could not be loaded</AlertTitle><AlertDescription><p>{detailError}</p></AlertDescription></Alert>
              ) : !batch ? (
                <LoadingCard>Loading the import…</LoadingCard>
              ) : (
                <>
                  <Card>
                    <CardHeader><CardTitle>Validation results</CardTitle><CardDescription>{batch.fileName}{batch.periodStart && batch.periodEnd ? ` · ${fmt.date(batch.periodStart)} to ${fmt.date(batch.periodEnd)}` : ""}</CardDescription></CardHeader>
                    <CardContent className="grid gap-3">
                      <StatRow items={[
                        { label: "Rows", value: fmt.int(batch.rowCount) },
                        { label: "Valid", value: fmt.int(batch.validRowCount) },
                        { label: "Invalid rows", value: batch.invalidRowCount, negative: batch.invalidRowCount > 0 },
                        { label: "Duplicate dates", value: duplicateDates, sub: `${batch.duplicateRowCount} rows`, negative: batch.duplicateRowCount > 0 },
                        { label: "Cash sales", value: fmt.lkr0(batch.cashSalesTotal), sub: "Valid rows" },
                        { label: "Card payments", value: fmt.lkr0(batch.cardSalesTotal), sub: "Valid rows" },
                        { label: "Coverage", value: coverage ? `${(coverage / 365).toFixed(1)} yrs` : "—", sub: coverage >= 365 ? "At least 1 year" : "Less than 1 year", negative: coverage < 365 },
                      ]} />
                      {batch.status === "INVALID" && (
                        <Alert variant="destructive"><AlertTriangle /><AlertTitle>This file cannot be approved</AlertTitle><AlertDescription>{invalidReason(batch)}</AlertDescription></Alert>
                      )}
                    </CardContent>
                  </Card>

                  <Card>
                    <CardHeader>
                      <CardTitle>Finance Controller approval</CardTitle>
                      <CardDescription>Only a Finance Controller can approve. Records are not used until then.</CardDescription>
                    </CardHeader>
                    <CardContent className="grid gap-3">
                      {batch.status === "APPROVED" ? (
                        <>
                          <Alert variant="info"><CheckCircle2 /><AlertTitle>Approved by {uploaderLabel(batch.approvedBy, me) === "You" ? "you" : "a Finance Controller"}</AlertTitle>
                            <AlertDescription>{fmt.dateTimeFull(local(batch.approvedAt))}. {fmt.int(batch.importedRowCount ?? 0)} rows recorded as daily sales{batch.excludedRowCount ? `, ${batch.excludedRowCount} invalid rows left out` : ""}.{batch.approvalNote ? ` Note: ${batch.approvalNote}` : ""}</AlertDescription></Alert>
                          {canApprove && (
                            <div className="flex flex-wrap items-center gap-2">
                              <Button variant="outline" disabled={busy} onClick={() => setReverseOpen(true)}><Undo2 />Reverse import</Button>
                              <span className="text-muted-foreground text-xs">Approved with wrong figures? Reverse it, then upload a corrected file.</span>
                            </div>
                          )}
                        </>
                      ) : batch.status === "REVERSED" ? (
                        <Alert><Undo2 /><AlertTitle>Reversed by {uploaderLabel(batch.reversedBy, me) === "You" ? "you" : "a Finance Controller"}</AlertTitle>
                          <AlertDescription>
                            {fmt.dateTimeFull(local(batch.reversedAt))}. Reason: {batch.reversalReason}. Its daily sales were deleted; it had been approved
                            {batch.approvedAt ? ` ${fmt.dateTimeFull(local(batch.approvedAt))}` : ""}. Upload a corrected file for these dates.
                          </AlertDescription></Alert>
                      ) : batch.status === "REJECTED" ? (
                        <Alert><XCircle /><AlertTitle>Rejected by {uploaderLabel(batch.rejectedBy, me) === "You" ? "you" : "a Finance Controller"}</AlertTitle>
                          <AlertDescription>{fmt.dateTimeFull(local(batch.rejectedAt))}. Reason: {batch.rejectionReason}. Upload a corrected file to try again.</AlertDescription></Alert>
                      ) : batch.status === "INVALID" ? (
                        <p className="text-muted-foreground text-sm">Correct the file and upload it again; an invalid file cannot be approved.</p>
                      ) : (
                        <>
                          {batch.invalidRowCount > 0 && batch.duplicateRowCount === 0 && (
                            <div className="flex items-center gap-2">
                              <Checkbox id="skip" checked={leaveOut} onCheckedChange={(v) => setLeaveOut(v === true)} disabled={!canApprove} />
                              <Label htmlFor="skip" className="font-normal">Leave out the {batch.invalidRowCount} invalid rows</Label>
                            </div>
                          )}
                          {!canApprove && <p className="text-muted-foreground flex items-center gap-1.5 text-xs"><Lock className="size-3" />Waiting for a Finance Controller to approve.</p>}
                          {canApprove && block && <p className="text-muted-foreground text-xs">{block}</p>}
                          {canApprove && (
                            <div className="flex gap-2">
                              <Button disabled={!!block || busy} onClick={() => setApproveOpen(true)}><Check />Approve import</Button>
                              <Button variant="outline" disabled={busy} onClick={() => setRejectOpen(true)}>Reject</Button>
                            </div>
                          )}
                        </>
                      )}
                    </CardContent>
                  </Card>

                  <Card>
                    <CardHeader><CardTitle>Data preview</CardTitle><CardDescription>Rows with problems and their reasons, or every row in the file</CardDescription></CardHeader>
                    <CardContent>
                      <Segmented value={view} onChange={setView} options={[{ value: "issues", label: "Issues", count: issues.length }, { value: "all", label: "All rows", count: fmt.int(rows.length) }]} />
                    </CardContent>
                    <DataTable<ApiSalesImportRow>
                      rows={view === "issues" ? issues : rows}
                      rowKey={(r) => String(r.rowNumber)}
                      defaultSort={{ key: "row", dir: "asc" }}
                      maxHeight={420}
                      empty={batch.fileError ? "The file could not be read, so it has no rows." : view === "issues" ? "No problems found." : "No rows."}
                      footer={(rs) => <><span>{fmt.int(rs.length)} rows shown</span><span>Values in LKR</span></>}
                      columns={[
                        { key: "row", header: "Row", numeric: true, sortValue: (r) => r.rowNumber, cell: (r) => <span className="text-muted-foreground">{r.rowNumber}</span> },
                        { key: "date", header: "date", sortValue: (r) => rowDateText(r), cell: (r) => <span className="font-mono text-xs">{r.saleDate ? fmt.date(r.saleDate) : rowDateText(r) || <span className="text-destructive">empty</span>}</span> },
                        { key: "cash", header: "cash_sales", numeric: true, sortValue: (r) => r.cashSalesAmount ?? -1, cell: (r) => cell(r.cashSalesAmount, r.raw?.cash_sales) },
                        { key: "card", header: "card_payments", numeric: true, sortValue: (r) => r.cardSalesAmount ?? -1, cell: (r) => cell(r.cardSalesAmount, r.raw?.card_payments) },
                        { key: "status", header: "Check", sortValue: (r) => r.status, cell: (r) => <StatusBadge status={rowStatusLabel(r.status)} /> },
                        { key: "issue", header: "Issue", cell: (r) => <span className={r.status === "VALID" ? "text-muted-foreground" : "text-destructive"}>{rowIssue(r)}</span> },
                      ]}
                    />
                  </Card>
                </>
              )}
            </div>
          </div>

          <Card>
            <CardHeader>
              <CardTitle>Import history</CardTitle>
              <CardDescription>{overview.ownImportsOnly ? "Your historical imports" : "Historical imports of this company"}, with who uploaded and decided each one. Select one to review it.</CardDescription>
            </CardHeader>
            <DataTable<ApiSalesImport>
              rows={overview.imports}
              rowKey={(r) => r.id}
              maxHeight="none"
              empty="No historical imports yet."
              onRowClick={(r) => setSelectedId(r.id)}
              rowClassName={(r) => (r.id === selectedId ? "bg-muted" : "")}
              columns={[
                { key: "file", header: "File", cell: (r) => r.fileName },
                { key: "period", header: "Period", cell: (r) => (r.periodStart && r.periodEnd ? fmt.range(r.periodStart, r.periodEnd) : "—") },
                { key: "rows", header: "Rows", numeric: true, cell: (r) => fmt.int(r.rowCount) },
                { key: "uploaded", header: "Uploaded", cell: (r) => <CellStack main={fmt.dateTime(local(r.uploadedAt))} sub={uploaderLabel(r.uploadedBy, me)} /> },
                { key: "status", header: "Status", cell: (r) => <StatusBadge status={importStatusLabel(r.status)} /> },
                { key: "by", header: "Decided by", cell: (r) => {
                  const at = r.reversedAt ?? r.approvedAt ?? r.rejectedAt
                  return at ? <CellStack main={uploaderLabel(r.reversedBy ?? r.approvedBy ?? r.rejectedBy, me)} sub={fmt.dateTime(local(at))} /> : "—"
                } },
              ]}
            />
          </Card>
        </>
      )}

      <Dialog open={approveOpen} onOpenChange={(o) => !busy && setApproveOpen(o)}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>Approve historical import?</DialogTitle>
            <DialogDescription>
              {batch && <>{fmt.int(batch.validRowCount)} rows{batch.periodStart && batch.periodEnd ? ` from ${fmt.date(batch.periodStart)} to ${fmt.date(batch.periodEnd)}` : ""} will be recorded as daily sales.{batch.invalidRowCount > 0 && ` ${batch.invalidRowCount} invalid rows will be left out.`} A Finance Controller or Company Admin can reverse it later if it turns out wrong.</>}
            </DialogDescription>
          </DialogHeader>
          <Field label="Approval note (optional)" htmlFor="appr-note">
            <Textarea id="appr-note" maxLength={500} value={note} onChange={(e) => setNote(e.target.value)} placeholder="e.g. Checked totals against audited 2023–2025 accounts" />
          </Field>
          <DialogFooter>
            <Button variant="outline" disabled={busy} onClick={() => setApproveOpen(false)}>Cancel</Button>
            <Button disabled={busy} onClick={() => void decide("approve")}>{busy && <Loader2 className="animate-spin" />}Approve and import</Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      <Dialog open={rejectOpen} onOpenChange={(o) => !busy && setRejectOpen(o)}>
        <DialogContent>
          <DialogHeader><DialogTitle>Reject this import?</DialogTitle><DialogDescription>The file will not be imported.</DialogDescription></DialogHeader>
          <Field label="Reason for rejection" htmlFor="rej" error={reasonErr}>
            <Textarea id="rej" maxLength={500} value={reason} onChange={(e) => { setReason(e.target.value); setReasonErr(null) }} placeholder="Tell the uploader what to fix" aria-invalid={!!reasonErr || undefined} />
          </Field>
          <DialogFooter>
            <Button variant="outline" disabled={busy} onClick={() => setRejectOpen(false)}>Cancel</Button>
            <Button variant="destructive" disabled={busy} onClick={() => void decide("reject")}>{busy && <Loader2 className="animate-spin" />}Reject import</Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      <Dialog open={reverseOpen} onOpenChange={(o) => !busy && setReverseOpen(o)}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>Reverse this import?</DialogTitle>
            <DialogDescription>
              {batch && <>The {fmt.int(batch.importedRowCount ?? 0)} daily sales it recorded
                {batch.periodStart && batch.periodEnd ? ` (${fmt.date(batch.periodStart)} to ${fmt.date(batch.periodEnd)})` : ""} are deleted and the
                forecast stops using its figures. The import stays in the history as reversed. Its dates and file can then be imported again.</>}
            </DialogDescription>
          </DialogHeader>
          <Field label="Reason for reversing" htmlFor="rev" error={reasonErr}>
            <Textarea id="rev" maxLength={500} value={reason} onChange={(e) => { setReason(e.target.value); setReasonErr(null) }}
              placeholder="e.g. Figures were for 2024, not 2025" aria-invalid={!!reasonErr || undefined} />
          </Field>
          <DialogFooter>
            <Button variant="outline" disabled={busy} onClick={() => setReverseOpen(false)}>Cancel</Button>
            <Button variant="destructive" disabled={busy} onClick={() => void decide("reverse")}>{busy && <Loader2 className="animate-spin" />}Reverse import</Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </>
  )
}

// A value, or the cell as written when it could not be read; a blank cell is shown as "empty", never as 0.
function cell(value: number | null, raw: string | undefined) {
  if (value != null) return fmt.amt(value)
  if (raw) return <span className="text-destructive font-mono text-xs">{raw}</span>
  return raw === "" ? <span className="text-destructive">empty</span> : "—"
}
