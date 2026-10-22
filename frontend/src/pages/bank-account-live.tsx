import { useCallback, useEffect, useMemo, useRef, useState } from "react"
import { Link } from "react-router-dom"
import { AlertTriangle, Download, FileUp, Info, Landmark, Lock, Pencil, Plus, RefreshCw, Settings2, Trash2 } from "lucide-react"
import { toast } from "sonner"
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Card, CardAction, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card"
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog"
import { Input } from "@/components/ui/input"
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select"
import { useAppState } from "@/components/app/app-state"
import { CellStack, DataTable } from "@/components/app/data-table"
import { LoadingCard } from "@/components/app/data-state"
import { Field } from "@/components/app/field"
import { PageHeader } from "@/components/app/page-header"
import { SearchInput } from "@/components/app/search-input"
import { Segmented } from "@/components/app/segmented"
import { StatusBadge } from "@/components/app/status-badge"
import { categoryText, colomboLocal } from "@/lib/api/adapters"
import { ApiError, errorMessage } from "@/lib/api/client"
import {
  flowledger, type ApiBalance, type ApiBankAccount, type ApiStatementImport, type ApiTransaction, type StatementError, type TransactionCategory,
} from "@/lib/api/flowledger"
import { BANK_TEMPLATE_COLUMNS, BANK_TEMPLATE_CSV, BANK_TEMPLATE_FILE_NAME, BANK_TEMPLATE_LIMITS, endedMonths, monthLabel } from "@/lib/bank-template"
import { fmt } from "@/lib/format"

const CATEGORY_LABEL: Record<TransactionCategory, string> = {
  CASH_SALES_DEPOSIT: "Cash sales deposit", CARD_SALES_SETTLEMENT: "Card settlement", CHEQUE_PAYMENT: "Cheque payment", OTHER: "Other", UNCLASSIFIED: "Unclassified",
}
/** Sales receipts are money in and cheque payments money out; the service checks the same. */
const CATEGORIES: Record<"CREDIT" | "DEBIT", TransactionCategory[]> = {
  CREDIT: ["UNCLASSIFIED", "CASH_SALES_DEPOSIT", "CARD_SALES_SETTLEMENT", "OTHER"],
  DEBIT: ["UNCLASSIFIED", "CHEQUE_PAYMENT", "OTHER"],
}
const MODE_LABEL: Record<string, string> = { STATEMENT_IMPORT: "Bank statement import", MANUAL: "Manual entry" }
const MAX_SHOWN_ERRORS = 20

interface BankData { account: ApiBankAccount | null; balance: ApiBalance | null; imports: ApiStatementImport[]; transactions: ApiTransaction[] }

/** A missing balance is a 404 from the service, not an error of the screen. */
const balanceOrNull = (c: string, id: string) =>
  flowledger.latestBalance(c, id).catch((e) => { if (e instanceof ApiError && e.status === 404) return null; throw e })

/**
 * The Bank Account screen with company data from bank-integration-service. Bank data arrives the
 * way the account's connection says: statement uploads, or balances and transactions entered by
 * hand. Finance Controllers and Company Admins enter data and classify transactions; the service
 * checks every permission and the connection rule again.
 */
export default function LiveBankAccountPage() {
  const { company, allow, reload, forecastInfo, today } = useAppState()
  const cid = company?.id ?? null
  const canEnter = allow("bank.enterData")
  const canImport = allow("bank.importMonthly")
  const canConfigure = allow("bank.configure")
  const [data, setData] = useState<BankData | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [q, setQ] = useState("")
  const [balanceOpen, setBalanceOpen] = useState(false)
  const [transactionOpen, setTransactionOpen] = useState(false)
  const [removing, setRemoving] = useState<ApiTransaction | null>(null)
  const [describing, setDescribing] = useState<ApiTransaction | null>(null)
  const generation = useRef(0)

  const load = useCallback(async () => {
    if (!cid) return
    const g = ++generation.current
    setError(null)
    try {
      const accounts = await flowledger.bankAccounts(cid)
      const account = accounts.find((a) => a.accountType === "CURRENT" && a.status === "ACTIVE") ?? null
      const [balance, imports, transactions] = account
        ? await Promise.all([balanceOrNull(cid, account.id), flowledger.statementImports(cid), flowledger.transactions(cid, account.id)])
        : [null, [], []]
      if (g === generation.current) setData({ account, balance, imports: imports.filter((i) => i.bankAccountId === account?.id), transactions })
    } catch (e) {
      if (g === generation.current) setError(errorMessage(e))
    }
  }, [cid])

  useEffect(() => { setData(null); void load() }, [load])

  /** After a change: this screen, and the dashboard and forecast that read the same bank data. */
  const changed = useCallback(() => { void load(); reload("bank") }, [load, reload])

  const rows = useMemo(() => {
    const s = q.trim().toLowerCase()
    return (data?.transactions ?? []).filter((t) =>
      !s || `${t.description ?? ""} ${t.chequeNumber ?? ""} ${t.amount} ${categoryText(t.category, t.otherDescription)}`.toLowerCase().includes(s))
  }, [data, q])
  const unclassified = (data?.transactions ?? []).filter((t) => t.category === "UNCLASSIFIED").length

  /** OTHER needs a description, so it goes through the description dialog instead. */
  async function classify(t: ApiTransaction, category: TransactionCategory, otherDescription: string | null = null) {
    if (!cid) return false
    try {
      await flowledger.classifyTransaction(cid, t.id, category, otherDescription)
      toast.success(`Marked as ${categoryText(category, otherDescription)}`)
      changed()
      return true
    } catch (e) {
      toast.error("The category was not saved", { description: errorMessage(e) })
      return false
    }
  }
  const pickCategory = (t: ApiTransaction, category: TransactionCategory) => {
    if (category === "OTHER") setDescribing(t)
    else if (category !== t.category) void classify(t, category)
  }

  const configure = canConfigure && <Button variant="outline" asChild><Link to="/users-settings?tab=bank"><Settings2 />Configure bank account</Link></Button>

  if (error) {
    return (
      <>
        <PageHeader title="Bank Account" />
        <Alert variant="destructive">
          <AlertTriangle /><AlertTitle>The bank account could not be loaded</AlertTitle>
          <AlertDescription className="grid justify-items-start gap-2">{error}<Button size="sm" variant="outline" onClick={() => void load()}><RefreshCw />Try again</Button></AlertDescription>
        </Alert>
      </>
    )
  }
  if (!data) return <><PageHeader title="Bank Account" /><LoadingCard>Loading the bank account…</LoadingCard></>

  const { account, balance } = data
  if (!account || !cid) {
    return (
      <>
        <PageHeader title="Bank Account" />
        <Card className="items-center gap-3 px-4 py-12 text-center text-sm">
          The company current account hasn't been set up yet.
          <span className="text-muted-foreground">A company admin sets up the active current account.</span>
          {configure}
        </Card>
      </>
    )
  }

  const manual = account.integrationMode === "MANUAL"
  const stale = !!forecastInfo?.run.balanceStale

  return (
    <>
      <PageHeader
        title="Bank Account"
        description="The company current account and its Actual Bank Balance — the latest balance the bank confirmed. Expected deposits and card settlement transfers are not added until bank data confirms them."
        actions={configure}
      />
      {stale && (
        <Alert variant="destructive">
          <AlertTriangle />
          <AlertTitle>The Actual Bank Balance may be out of date</AlertTitle>
          <AlertDescription>The latest confirmed balance is {forecastInfo!.run.dataQuality.balanceAgeDays} days old. {manual ? "Record a recent balance." : "Import a recent bank statement."}</AlertDescription>
        </Alert>
      )}

      <Card>
        <CardHeader>
          <CardTitle>{account.bankName} · {account.accountName}</CardTitle>
          <CardDescription>Current Account · <span className="font-mono">{account.accountNumberLast4 ? `•••• ${account.accountNumberLast4}` : "Number not provided"}</span></CardDescription>
          <CardAction><Badge variant="outline">{MODE_LABEL[account.integrationMode] ?? account.integrationMode}</Badge></CardAction>
        </CardHeader>
        <CardContent className="grid gap-5 lg:grid-cols-[minmax(0,1fr)_minmax(0,1.4fr)]">
          {balance ? (
            <div className="grid content-start gap-1">
              <div className="text-muted-foreground text-xs">Actual Bank Balance</div>
              <div className="text-[28px] leading-tight font-semibold tracking-tight tabular">
                <span className="text-muted-foreground mr-1 text-sm font-medium">{balance.currency}</span>{fmt.amt(balance.balanceAmount)}
              </div>
              <div className="text-muted-foreground text-xs">As of {fmt.dateTimeFull(colomboLocal(balance.balanceAsOf))} · {balance.sourceType === "MANUAL" ? "entered manually" : "from a bank statement"}</div>
            </div>
          ) : (
            <div className="grid content-start gap-1">
              <div className="text-muted-foreground text-xs">Actual Bank Balance</div>
              <div className="text-[17px] leading-tight font-semibold">No confirmed balance yet</div>
              <div className="text-muted-foreground text-xs">{manual ? "Record the balance the bank confirms." : "Import a bank statement to record the balance the bank confirms."}</div>
            </div>
          )}
          <dl className="grid grid-cols-[minmax(130px,40%)_1fr] gap-x-4 gap-y-2 text-[13px]">
            {([
              ["Bank", account.bankName], ["Branch", account.branchName ?? "Not provided"], ["Account name", account.accountName],
              ["Account number", <span className="font-mono">{account.accountNumberLast4 ? `•••• ${account.accountNumberLast4}` : "Not provided"}</span>],
              ["Account type", "Current Account"], ["Currency", account.currency], ["Bank data", MODE_LABEL[account.integrationMode] ?? account.integrationMode],
            ] as const).map(([k, v]) => (
              <div key={k} className="contents"><dt className="text-muted-foreground">{k}</dt><dd className="font-medium break-words">{v}</dd></div>
            ))}
          </dl>
        </CardContent>
      </Card>

      {manual
        ? <ManualEntryCard canEnter={canEnter} onBalance={() => setBalanceOpen(true)} onTransaction={() => setTransactionOpen(true)} />
        : <MonthlyImportCard companyId={cid} account={account} imports={data.imports} today={today} canImport={canImport} onImported={changed} />}

      <Alert variant="info">
        <Info />
        <AlertTitle>How sales money reaches this account</AlertTitle>
        <AlertDescription>
          Cash sales are deposited into this account. Card sales first arrive in a separate card settlement account — not tracked in FlowLedger as a
          company bank account — and are transferred here the next morning (Friday to Sunday settlements on Monday). Give each one its category in the monthly template so
          Forecast vs Actual counts it as a sales receipt; cheque payments carry their cheque number, so the cheque shows as cleared.
        </AlertDescription>
      </Alert>

      <Card>
        <CardHeader>
          <CardTitle>Transactions</CardTitle>
          <CardDescription>
            {manual ? "Entered by hand from the bank's record." : "Imported from bank statements."}
            {unclassified > 0 && <> {fmt.int(unclassified)} not classified yet — they don't count in Forecast vs Actual.</>}
          </CardDescription>
        </CardHeader>
        <CardContent><SearchInput value={q} onChange={setQ} placeholder="Search description, cheque number, amount or category" /></CardContent>
        <DataTable<ApiTransaction>
          rows={rows}
          rowKey={(r) => r.id}
          defaultSort={{ key: "date", dir: "desc" }}
          empty={q ? "No transactions match this search." : manual ? "No transactions entered yet." : "No transactions imported yet."}
          footer={(rs) => <><span>{fmt.int(rs.length)} transactions</span><span>{manual ? "Manual entries can be deleted" : "Transactions already imported are never imported twice"}</span></>}
          columns={[
            { key: "date", header: "Date", sortValue: (r) => r.transactionDate, cell: (r) => <span className="whitespace-nowrap">{fmt.short(r.transactionDate)}</span> },
            {
              key: "desc", header: "Description", cell: (r) => (
                <CellStack main={r.description ?? "—"} sub={[r.chequeNumber && `Cheque ${r.chequeNumber}`, r.entrySource === "MANUAL" && "Entered manually"].filter(Boolean).join(" · ") || null} />
              ),
            },
            { key: "debit", header: "Debit", numeric: true, sortValue: (r) => (r.direction === "DEBIT" ? r.amount : 0), cell: (r) => (r.direction === "DEBIT" ? fmt.amt(r.amount) : "") },
            { key: "credit", header: "Credit", numeric: true, sortValue: (r) => (r.direction === "CREDIT" ? r.amount : 0), cell: (r) => (r.direction === "CREDIT" ? fmt.amt(r.amount) : "") },
            { key: "balance", header: "Running balance", numeric: true, cell: (r) => (r.runningBalance == null ? <span className="text-muted-foreground">—</span> : fmt.amt(r.runningBalance)) },
            {
              key: "category", header: "Category", sortValue: (r) => categoryText(r.category, r.otherDescription), cell: (r) => canEnter ? (
                <div className="flex items-center gap-1">
                  <Select value={r.category} onValueChange={(v) => pickCategory(r, v as TransactionCategory)}>
                    <SelectTrigger size="sm" className="w-56 [&>span]:truncate" aria-label={`Category of ${r.description ?? "transaction"}`}><SelectValue /></SelectTrigger>
                    <SelectContent>
                      {CATEGORIES[r.direction].map((c) => <SelectItem key={c} value={c}>{c === r.category ? categoryText(c, r.otherDescription) : CATEGORY_LABEL[c]}</SelectItem>)}
                    </SelectContent>
                  </Select>
                  {r.category === "OTHER" && (
                    <Button variant="ghost" size="icon-sm" aria-label="Change what this payment was" onClick={() => setDescribing(r)}><Pencil /></Button>
                  )}
                </div>
              ) : r.category === "UNCLASSIFIED" ? <Badge variant="attention">Unclassified</Badge> : <Badge variant="outline">{categoryText(r.category, r.otherDescription)}</Badge>,
            },
            {
              key: "act", header: "", cell: (r) => canEnter && r.entrySource === "MANUAL"
                ? <Button variant="ghost" size="icon-sm" aria-label="Delete this entry" onClick={() => setRemoving(r)}><Trash2 /></Button>
                : null,
            },
          ]}
        />
      </Card>

      <ManualBalanceDialog companyId={cid} accountId={account.id} today={today} open={balanceOpen} onOpenChange={setBalanceOpen} onSaved={changed} />
      <ManualTransactionDialog companyId={cid} accountId={account.id} today={today} open={transactionOpen} onOpenChange={setTransactionOpen} onSaved={changed} />
      <DeleteEntryDialog companyId={cid} accountId={account.id} entry={removing} onClose={() => setRemoving(null)} onDeleted={changed} />
      <OtherDescriptionDialog entry={describing} onClose={() => setDescribing(null)} onSave={(t, text) => classify(t, "OTHER", text)} />
    </>
  )
}

function ManualEntryCard({ canEnter, onBalance, onTransaction }: { canEnter: boolean; onBalance: () => void; onTransaction: () => void }) {
  return (
    <Card>
      <CardHeader>
        <CardTitle>Manual entry</CardTitle>
        <CardDescription>
          This account's bank data is typed in from the bank's own record — its online banking or passbook. Record the balance the bank confirms, and
          each transaction with its category. The forecast starts from the latest balance.
        </CardDescription>
      </CardHeader>
      <CardContent className="flex flex-wrap gap-2">
        {canEnter ? (
          <>
            <Button size="sm" onClick={onBalance}><Landmark />Record balance</Button>
            <Button size="sm" variant="outline" onClick={onTransaction}><Plus />Add transaction</Button>
          </>
        ) : <p className="text-muted-foreground text-sm">Only a Finance Controller or Company Admin can enter bank data.</p>}
      </CardContent>
    </Card>
  )
}

/** Builds the CSV template in the browser — no server needed. */
function downloadBankTemplate() {
  const url = URL.createObjectURL(new Blob([BANK_TEMPLATE_CSV], { type: "text/csv" }))
  const a = Object.assign(document.createElement("a"), { href: url, download: BANK_TEMPLATE_FILE_NAME })
  a.click()
  URL.revokeObjectURL(url)
}

const FORMAT_LABEL: Record<string, string> = { FLOWLEDGER_MONTHLY_CSV: "Monthly template", TRANSACTION_DETAILS_CSV: "Bank statement" }

/**
 * After each month ends, the Accountant (or a Finance Controller or Company Admin) fills the monthly
 * template from the bank statement — every transaction with its category — and uploads it here.
 * The current month is never imported; Forecast vs Actual compares up to the end of last month.
 */
function MonthlyImportCard({ companyId, account, imports, today, canImport, onImported }: {
  companyId: string; account: ApiBankAccount; imports: ApiStatementImport[]; today: string; canImport: boolean; onImported: () => void
}) {
  const input = useRef<HTMLInputElement>(null)
  const months = useMemo(() => endedMonths(today), [today])
  const imported = useMemo(() => new Set(imports.filter((i) => i.status === "IMPORTED" && i.fileFormat === "FLOWLEDGER_MONTHLY_CSV")
    .map((i) => i.periodStart?.slice(0, 7))), [imports])
  const [month, setMonth] = useState(() => months.find((m) => !imported.has(m)) ?? months[0])
  const [uploading, setUploading] = useState(false)
  const [removing, setRemoving] = useState<ApiStatementImport | null>(null)
  const [busy, setBusy] = useState(false)
  const [rejected, setRejected] = useState<{ file: string; message: string; errors: StatementError[] } | null>(null)

  async function upload(file: File) {
    setUploading(true)
    setRejected(null)
    try {
      const result = await flowledger.uploadMonthlyTransactions(companyId, account.id, month, file)
      toast.success(`${monthLabel(month)} imported`, { description: `${fmt.int(result.importedRowCount)} transactions, each with its category` })
      onImported()
    } catch (e) {
      const errors = e instanceof ApiError && Array.isArray(e.problem.errors) ? (e.problem.errors as StatementError[]) : []
      setRejected({ file: file.name, message: errorMessage(e), errors })
      if (errors.length) onImported() // the rejected file is listed in the history
    } finally {
      setUploading(false)
      if (input.current) input.current.value = ""
    }
  }

  async function remove() {
    if (!removing) return
    setBusy(true)
    try {
      await flowledger.removeStatementImport(companyId, removing.id)
      toast("Import removed", { description: `${removing.filename}: its transactions and balance were deleted. You can upload the month again.` })
      setRemoving(null)
      onImported()
    } catch (e) {
      toast.error("The import was not removed", { description: errorMessage(e) })
    } finally {
      setBusy(false)
    }
  }

  return (
    <>
      <div className="grid gap-4 lg:grid-cols-2">
        <Card>
          <CardHeader>
            <CardTitle>Monthly bank transactions</CardTitle>
            <CardDescription>
              After a month ends, copy that month's rows from the bank's "Transaction details" report into the template exactly as the bank
              prints them, add each row's Category, and upload it. The current month is imported after it ends. Every row is checked; a file with
              any error is not imported at all.
            </CardDescription>
          </CardHeader>
          <CardContent className="grid gap-3">
            {canImport ? (
              <>
                <Field label="Month" help={imported.has(month) ? "Already imported — remove that import below to replace it." : BANK_TEMPLATE_LIMITS}>
                  <Select value={month} onValueChange={(v) => { setMonth(v); setRejected(null) }}>
                    <SelectTrigger className="w-56" aria-label="Month"><SelectValue /></SelectTrigger>
                    <SelectContent>
                      {months.map((m) => <SelectItem key={m} value={m}>{monthLabel(m)}{imported.has(m) ? " · imported" : ""}</SelectItem>)}
                    </SelectContent>
                  </Select>
                </Field>
                <div className="flex flex-wrap gap-2">
                  <input ref={input} type="file" accept=".csv,text/csv" className="hidden" onChange={(e) => { const f = e.target.files?.[0]; if (f) void upload(f) }} />
                  <Button size="sm" disabled={uploading || imported.has(month)} onClick={() => input.current?.click()}>
                    <FileUp />{uploading ? "Importing…" : `Upload ${monthLabel(month)}`}
                  </Button>
                  <Button size="sm" variant="outline" onClick={downloadBankTemplate}><Download />Template</Button>
                </div>
              </>
            ) : (
              <p className="text-muted-foreground flex items-center gap-1.5 text-sm"><Lock className="size-3.5" />Your role can view bank data but not import it.</p>
            )}
            {rejected && (
              <Alert variant="destructive">
                <AlertTriangle />
                <AlertTitle>{rejected.file} was not imported</AlertTitle>
                <AlertDescription>
                  <p>{rejected.message}</p>
                  {rejected.errors.length > 0 && (
                    <ul className="mt-1 list-disc pl-4 text-xs">
                      {rejected.errors.slice(0, MAX_SHOWN_ERRORS).map((err, i) => (
                        <li key={i}>{err.line != null && <>Line {err.line}{err.field && <>, {err.field}</>}: </>}{err.message}</li>
                      ))}
                      {rejected.errors.length > MAX_SHOWN_ERRORS && <li>…and {fmt.int(rejected.errors.length - MAX_SHOWN_ERRORS)} more</li>}
                    </ul>
                  )}
                </AlertDescription>
              </Alert>
            )}
          </CardContent>
        </Card>
        <Card>
          <CardHeader>
            <CardTitle>Template columns</CardTitle>
            <CardDescription>Column names must match exactly (first row). Save from Excel as CSV UTF-8.</CardDescription>
          </CardHeader>
          <CardContent>
            <div className="overflow-hidden rounded-md border text-[13px]">
              <div className="bg-muted text-muted-foreground grid grid-cols-[130px_1fr] gap-3 px-3 py-2 text-xs font-medium"><span>Column</span><span>Format</span></div>
              {BANK_TEMPLATE_COLUMNS.map(([c, d]) => (
                <div key={c} className="grid grid-cols-[130px_1fr] gap-3 border-t px-3 py-2"><span className="font-mono text-xs break-all">{c}</span><span>{d}</span></div>
              ))}
            </div>
          </CardContent>
        </Card>
      </div>

      <Card>
        <CardHeader><CardTitle>Import history</CardTitle><CardDescription>Each month is imported once. Remove an import to replace it.</CardDescription></CardHeader>
        <DataTable<ApiStatementImport>
          rows={imports}
          rowKey={(r) => r.id}
          defaultSort={{ key: "at", dir: "desc" }}
          maxHeight={320}
          empty="No bank transactions imported yet."
          columns={[
            { key: "at", header: "Uploaded", sortValue: (r) => r.importedAt, cell: (r) => <span className="whitespace-nowrap">{fmt.dateTime(colomboLocal(r.importedAt))}</span> },
            {
              key: "file", header: "File", cell: (r) => (
                <CellStack main={r.filename} sub={[FORMAT_LABEL[r.fileFormat] ?? r.fileFormat, r.periodStart && r.periodEnd && `${fmt.date(r.periodStart)} – ${fmt.date(r.periodEnd)}`].filter(Boolean).join(" · ")} />
              ),
            },
            {
              key: "status", header: "Status", cell: (r) => r.status === "IMPORTED" ? <StatusBadge status="Approved" label="Imported" />
                : r.status === "REMOVED" ? <StatusBadge status="Cancelled" label="Removed" /> : <StatusBadge status="Rejected" label="Rejected" />,
            },
            {
              key: "rows", header: "Rows", cell: (r) => r.status === "REMOVED"
                ? <span className="text-muted-foreground text-xs">Removed {r.removedAt ? fmt.dateTime(colomboLocal(r.removedAt)) : ""} · its {fmt.int(r.importedRowCount)} transactions were deleted</span>
                : r.status === "IMPORTED"
                  ? <span className="text-muted-foreground text-xs">{fmt.int(r.importedRowCount)} transactions{r.duplicateRowCount ? ` · ${fmt.int(r.duplicateRowCount)} already imported` : ""}</span>
                  : <span className="text-muted-foreground text-xs">{r.errorSummary ?? "Invalid file"}</span>,
            },
            {
              key: "act", header: "", cell: (r) => canImport && r.status !== "REMOVED"
                ? <Button variant="ghost" size="icon-sm" aria-label={`Remove ${r.filename}`} onClick={() => setRemoving(r)}><Trash2 /></Button>
                : null,
            },
          ]}
        />
      </Card>
      <Dialog open={!!removing} onOpenChange={(o) => !o && setRemoving(null)}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>Remove {removing?.filename}?</DialogTitle>
            <DialogDescription>
              {removing?.status === "IMPORTED"
                ? <>The {fmt.int(removing.importedRowCount)} transactions this import added and the balance it recorded are deleted; the Actual Bank
                  Balance goes back to the one before. The import stays in this list as removed, and you can upload the month again.</>
                : <>The rejected file is marked as removed. Nothing was imported from it.</>}
            </DialogDescription>
          </DialogHeader>
          <DialogFooter>
            <Button variant="outline" onClick={() => setRemoving(null)}>Cancel</Button>
            <Button variant="destructive" disabled={busy} onClick={() => void remove()}>Remove import</Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </>
  )
}

/** Numbers as typed: commas allowed, at most two decimal places. */
const parseAmount = (text: string) => {
  const clean = text.replace(/,/g, "").trim()
  return /^-?\d+(\.\d{1,2})?$/.test(clean) ? Number(clean) : null
}

function ManualBalanceDialog({ companyId, accountId, today, open, onOpenChange, onSaved }: {
  companyId: string; accountId: string; today: string; open: boolean; onOpenChange: (o: boolean) => void; onSaved: () => void
}) {
  const [amount, setAmount] = useState("")
  const [date, setDate] = useState(today)
  const [time, setTime] = useState("")
  const [err, setErr] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  useEffect(() => {
    if (!open) return
    setAmount(""); setDate(today); setTime(colomboLocal(new Date()).slice(11, 16)); setErr(null)
  }, [open, today])

  async function submit() {
    const value = parseAmount(amount)
    if (value == null) return setErr("Enter the balance, with at most two decimal places.")
    if (!date || !time) return setErr("Enter the date and time of the balance.")
    setBusy(true)
    try {
      // Times are Colombo time, which has no daylight saving.
      await flowledger.recordManualBalance(companyId, accountId, value, `${date}T${time}:00+05:30`)
      toast.success("Balance recorded", { description: `LKR ${fmt.amt(value)} as of ${fmt.date(date)} ${time}` })
      onOpenChange(false); onSaved()
    } catch (e) {
      setErr(errorMessage(e))
    } finally {
      setBusy(false)
    }
  }

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent>
        <DialogHeader><DialogTitle>Record balance</DialogTitle><DialogDescription>The balance the bank shows at a moment in time. Use a minus sign for an overdraft.</DialogDescription></DialogHeader>
        <Field label="Balance (LKR)" htmlFor="mb-amount" error={err}>
          <Input id="mb-amount" inputMode="decimal" className="tabular" placeholder="0.00" value={amount} onChange={(e) => setAmount(e.target.value)} />
        </Field>
        <div className="grid grid-cols-2 gap-3">
          <Field label="Date" htmlFor="mb-date"><Input id="mb-date" type="date" max={today} value={date} onChange={(e) => setDate(e.target.value)} /></Field>
          <Field label="Time" htmlFor="mb-time"><Input id="mb-time" type="time" value={time} onChange={(e) => setTime(e.target.value)} /></Field>
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={() => onOpenChange(false)}>Cancel</Button>
          <Button disabled={busy || !amount.trim()} onClick={submit}>Record balance</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}

function ManualTransactionDialog({ companyId, accountId, today, open, onOpenChange, onSaved }: {
  companyId: string; accountId: string; today: string; open: boolean; onOpenChange: (o: boolean) => void; onSaved: () => void
}) {
  const [direction, setDirection] = useState<"CREDIT" | "DEBIT">("CREDIT")
  const [date, setDate] = useState(today)
  const [amount, setAmount] = useState("")
  const [description, setDescription] = useState("")
  const [cheque, setCheque] = useState("")
  const [category, setCategory] = useState<TransactionCategory>("UNCLASSIFIED")
  const [other, setOther] = useState("")
  const [errors, setErrors] = useState<Record<string, string>>({})
  const [busy, setBusy] = useState(false)
  useEffect(() => {
    if (!open) return
    setDirection("CREDIT"); setDate(today); setAmount(""); setDescription(""); setCheque(""); setCategory("UNCLASSIFIED"); setOther(""); setErrors({})
  }, [open, today])

  const changeDirection = (d: "CREDIT" | "DEBIT") => {
    setDirection(d)
    if (!CATEGORIES[d].includes(category)) setCategory("UNCLASSIFIED")
  }

  async function submit() {
    const value = parseAmount(amount)
    if (value == null || value <= 0) return setErrors({ amount: "Enter an amount above zero, with at most two decimal places." })
    if (cheque && !/^\d+$/.test(cheque)) return setErrors({ chequeNumber: "Use digits only." })
    if (category === "OTHER" && !other.trim()) return setErrors({ otherDescription: "Say what this was, for example Electricity." })
    setBusy(true)
    try {
      await flowledger.addManualTransaction(companyId, accountId, {
        transactionDate: date, direction, amount: value, description: description.trim(),
        chequeNumber: direction === "DEBIT" && cheque ? cheque : null, category: category === "UNCLASSIFIED" ? null : category,
        otherDescription: category === "OTHER" ? other.trim() : null,
      })
      toast.success("Transaction added", { description: `${direction === "CREDIT" ? "Money in" : "Money out"} · LKR ${fmt.amt(value)}` })
      onOpenChange(false); onSaved()
    } catch (e) {
      setErrors(e instanceof ApiError && Object.keys(e.fieldErrors).length ? e.fieldErrors : { description: errorMessage(e) })
    } finally {
      setBusy(false)
    }
  }

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent>
        <DialogHeader><DialogTitle>Add transaction</DialogTitle><DialogDescription>One transaction exactly as the bank shows it.</DialogDescription></DialogHeader>
        <Segmented<"CREDIT" | "DEBIT"> value={direction} onChange={changeDirection} options={[{ value: "CREDIT", label: "Money in (credit)" }, { value: "DEBIT", label: "Money out (debit)" }]} />
        <div className="grid grid-cols-2 gap-3">
          <Field label="Date" htmlFor="mt-date" error={errors.transactionDate}><Input id="mt-date" type="date" max={today} value={date} onChange={(e) => setDate(e.target.value)} /></Field>
          <Field label="Amount (LKR)" htmlFor="mt-amount" error={errors.amount}>
            <Input id="mt-amount" inputMode="decimal" className="tabular" placeholder="0.00" value={amount} onChange={(e) => setAmount(e.target.value)} />
          </Field>
        </div>
        <Field label="Description" htmlFor="mt-desc" error={errors.description}>
          <Input id="mt-desc" placeholder={direction === "CREDIT" ? "e.g. Cash deposit" : "e.g. Cheque 000123"} value={description} onChange={(e) => setDescription(e.target.value)} />
        </Field>
        {direction === "DEBIT" && (
          <Field label="Cheque number" htmlFor="mt-cheque" error={errors.chequeNumber} help="Optional">
            <Input id="mt-cheque" inputMode="numeric" className="font-mono" value={cheque} onChange={(e) => setCheque(e.target.value.replace(/\D/g, ""))} />
          </Field>
        )}
        <Field label="Category" error={errors.category} help="Sales receipts and cheque payments count in Forecast vs Actual.">
          <Select value={category} onValueChange={(v) => setCategory(v as TransactionCategory)}>
            <SelectTrigger className="w-full" aria-label="Category"><SelectValue /></SelectTrigger>
            <SelectContent>{CATEGORIES[direction].map((c) => <SelectItem key={c} value={c}>{CATEGORY_LABEL[c]}</SelectItem>)}</SelectContent>
          </Select>
        </Field>
        {category === "OTHER" && (
          <Field label={direction === "DEBIT" ? "What was this payment?" : "What was this receipt?"} htmlFor="mt-other" error={errors.otherDescription}
            help={`Shows as "Other - ${other.trim() || "…"}"`}>
            <Input id="mt-other" maxLength={120} placeholder="e.g. Electricity" value={other} onChange={(e) => setOther(e.target.value)} />
          </Field>
        )}
        <DialogFooter>
          <Button variant="outline" onClick={() => onOpenChange(false)}>Cancel</Button>
          <Button disabled={busy || !amount.trim() || !description.trim() || !date} onClick={submit}>Add transaction</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}

/** "Other" needs to say what the payment was, so it shows as "Other - Electricity". */
function OtherDescriptionDialog({ entry, onClose, onSave }: {
  entry: ApiTransaction | null; onClose: () => void; onSave: (t: ApiTransaction, text: string) => Promise<boolean>
}) {
  const [text, setText] = useState("")
  const [busy, setBusy] = useState(false)
  useEffect(() => { if (entry) setText(entry.otherDescription ?? "") }, [entry])

  async function save() {
    if (!entry || !text.trim()) return
    setBusy(true)
    if (await onSave(entry, text.trim())) onClose()
    setBusy(false)
  }

  return (
    <Dialog open={!!entry} onOpenChange={(o) => !o && onClose()}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>What was this {entry?.direction === "DEBIT" ? "payment" : "receipt"}?</DialogTitle>
          <DialogDescription>
            {entry && <>{fmt.date(entry.transactionDate)} · {entry.description} · LKR {fmt.amt(entry.amount)}. </>}
            It shows as "Other - " followed by what you write.
          </DialogDescription>
        </DialogHeader>
        <Field label="Description" htmlFor="other-text" help="e.g. Electricity, Loan repayment, Bank charges">
          <Input id="other-text" maxLength={120} value={text} onChange={(e) => setText(e.target.value)} onKeyDown={(e) => { if (e.key === "Enter") void save() }} />
        </Field>
        <DialogFooter>
          <Button variant="outline" onClick={onClose}>Cancel</Button>
          <Button disabled={busy || !text.trim()} onClick={() => void save()}>Save</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}

function DeleteEntryDialog({ companyId, accountId, entry, onClose, onDeleted }: {
  companyId: string; accountId: string; entry: ApiTransaction | null; onClose: () => void; onDeleted: () => void
}) {
  const [busy, setBusy] = useState(false)

  async function remove() {
    if (!entry) return
    setBusy(true)
    try {
      await flowledger.deleteManualTransaction(companyId, accountId, entry.id)
      toast("Transaction deleted", { description: "The deletion is kept in the audit history." })
      onClose(); onDeleted()
    } catch (e) {
      toast.error("The transaction was not deleted", { description: errorMessage(e) })
    } finally {
      setBusy(false)
    }
  }

  return (
    <Dialog open={!!entry} onOpenChange={(o) => !o && onClose()}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>Delete this transaction?</DialogTitle>
          <DialogDescription>
            {entry && <>{fmt.date(entry.transactionDate)} · {entry.description} · LKR {fmt.amt(entry.amount)} {entry.direction === "CREDIT" ? "in" : "out"}. </>}
            Delete an entry only if it was typed in by mistake; it stops counting in Forecast vs Actual.
          </DialogDescription>
        </DialogHeader>
        <DialogFooter>
          <Button variant="outline" onClick={onClose}>Cancel</Button>
          <Button variant="destructive" disabled={busy} onClick={remove}>Delete transaction</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}
