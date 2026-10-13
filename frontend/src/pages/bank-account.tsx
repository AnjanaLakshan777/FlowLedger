import { useMemo, useState } from "react"
import { Link } from "react-router-dom"
import { AlertTriangle, Info, RefreshCw, Settings2 } from "lucide-react"
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Card, CardAction, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card"
import { useAppState } from "@/components/app/app-state"
import { CellStack, DataTable } from "@/components/app/data-table"
import { PageHeader } from "@/components/app/page-header"
import { SearchInput } from "@/components/app/search-input"
import { StateDot } from "@/components/app/state-dot"
import { bankStatus, StatusBadge } from "@/components/app/status-badge"
import type { BankTransaction } from "@/data/types"
import { requiresBackend } from "@/lib/demo"
import { fmt } from "@/lib/format"
import { cn } from "@/lib/utils"

/** The company's one current bank account (MVP). */
export default function BankAccountPage() {
  const { bankAccount: acct, transactions, allow } = useAppState()
  const [q, setQ] = useState("")
  const rows = useMemo(() => {
    const s = q.trim().toLowerCase()
    return transactions.filter((t) => !s || `${t.description} ${t.reference} ${t.amount}`.toLowerCase().includes(s))
  }, [transactions, q])
  // System Admin configures the integration; Finance Controller and System Admin can retry a failed sync
  const canConfigure = allow("bank.configure")
  const canRetry = allow("bank.retrySync")
  const configure = canConfigure && <Button variant="outline" asChild><Link to="/users-settings?tab=bank"><Settings2 />Configure bank integration</Link></Button>

  if (!acct) {
    return (
      <>
        <PageHeader title="Bank Account" />
        <Card className="items-center gap-3 px-4 py-12 text-center text-sm">
          The company current account hasn't been set up yet.
          {configure}
        </Card>
      </>
    )
  }

  const failed = acct.sync_status !== "Synced"
  const retry = () => requiresBackend(`Retrying sync for ${acct.bank} ${acct.account_masked}`)

  return (
    <>
      <PageHeader
        title="Bank Account"
        description="The company current account and its Actual Bank Balance — the latest balance reported by the bank. Expected deposits and card settlement transfers are not added until bank data confirms them."
        actions={configure}
      />
      {failed && (
        <Alert variant="destructive">
          <AlertTriangle />
          <AlertTitle>Bank sync failed — data may be out of date</AlertTitle>
          <AlertDescription>
            <p>{acct.error} Last successful sync {fmt.dateTimeFull(acct.last_sync)}.</p>
            {canRetry && <Button variant="outline" size="sm" className="mt-1" onClick={retry}><RefreshCw />Retry sync</Button>}
          </AlertDescription>
        </Alert>
      )}

      <Card className={cn(failed && "border-destructive/45")}>
        <CardHeader>
          <CardTitle>{acct.bank} · {acct.account_name}</CardTitle>
          <CardDescription>Current Account · <span className="font-mono">{acct.account_masked}</span></CardDescription>
          <CardAction>{failed ? <Badge variant="destructive-soft"><AlertTriangle />Sync failed</Badge> : <StatusBadge status="Synced" />}</CardAction>
        </CardHeader>
        <CardContent className="grid gap-5 lg:grid-cols-[minmax(0,1fr)_minmax(0,1.4fr)]">
          <div className="grid content-start gap-1">
            <div className="text-muted-foreground text-xs">{failed ? "Actual Bank Balance — last received (may be out of date)" : "Actual Bank Balance"}</div>
            <div className="text-[28px] leading-tight font-semibold tracking-tight tabular">
              <span className="text-muted-foreground mr-1 text-sm font-medium">{acct.currency}</span>{fmt.amt(acct.balance)}
            </div>
            <div className="text-muted-foreground text-xs">As of {fmt.dateTimeFull(acct.balance_as_of)}</div>
          </div>
          <dl className="grid grid-cols-[minmax(130px,40%)_1fr] gap-x-4 gap-y-2 text-[13px]">
            {([
              ["Bank", acct.bank], ["Account name", acct.account_name], ["Account number", <span className="font-mono">{acct.account_masked}</span>],
              ["Account type", "Current Account"], ["Branch", acct.branch], ["Currency", acct.currency],
              ["Last successful sync", <StateDot tone={failed ? "error" : "ok"}>{fmt.dateTimeFull(acct.last_sync)}</StateDot>],
              ["Sync status", failed ? "Failed" : "Synced"], ["Connection", acct.integration],
            ] as const).map(([k, v]) => (
              <div key={k} className="contents"><dt className="text-muted-foreground">{k}</dt><dd className="font-medium break-words">{v}</dd></div>
            ))}
          </dl>
        </CardContent>
        <CardContent className="flex flex-wrap gap-2 border-t pt-4">
          {canRetry && failed && <Button variant="outline" size="sm" onClick={retry}><RefreshCw />Retry sync</Button>}
        </CardContent>
      </Card>

      <Alert variant="info">
        <Info />
        <AlertTitle>How sales money reaches this account</AlertTitle>
        <AlertDescription>
          Cash sales are deposited into this account. Card sales first arrive in a separate card settlement account — not tracked in FlowLedger as a
          company bank account — and are transferred here the next morning (Friday to Sunday settlements on Monday). Both are the arrival of sales
          already recorded, not new income.
        </AlertDescription>
      </Alert>

      <Card>
        <CardHeader>
          <CardTitle>Transactions</CardTitle>
          <CardDescription>Received from bank data for {acct.bank} {acct.account_masked}</CardDescription>
        </CardHeader>
        <CardContent><SearchInput value={q} onChange={setQ} placeholder="Search description, reference or amount" /></CardContent>
        <DataTable<BankTransaction>
          rows={rows}
          rowKey={(r) => r.txn_id}
          defaultSort={{ key: "date", dir: "desc" }}
          empty={q ? "No transactions match this search." : "No transactions received yet."}
          footer={(rs) => <><span>{rs.length} transactions</span><span>Excluded duplicates are not part of the Actual Bank Balance</span></>}
          columns={[
            { key: "date", header: "Date", sortValue: (r) => r.txn_date + r.txn_id, cell: (r) => <span className="whitespace-nowrap">{fmt.short(r.txn_date)}</span> },
            { key: "value", header: "Value date", cell: (r) => <span className="text-muted-foreground whitespace-nowrap">{r.value_date ? fmt.short(r.value_date) : "Not provided"}</span> },
            { key: "desc", header: "Description", cell: (r) => <CellStack main={r.description} sub={<span className="font-mono">{r.reference}</span>} /> },
            { key: "debit", header: "Debit", numeric: true, sortValue: (r) => (r.amount < 0 ? -r.amount : 0), cell: (r) => (r.amount < 0 ? fmt.amt(-r.amount) : "") },
            { key: "credit", header: "Credit", numeric: true, sortValue: (r) => (r.amount > 0 ? r.amount : 0), cell: (r) => (r.amount > 0 ? fmt.amt(r.amount) : "") },
            { key: "balance", header: "Actual Bank Balance", numeric: true, cell: (r) => (r.balance_after == null ? <span className="text-muted-foreground">Not provided</span> : fmt.amt(r.balance_after)) },
            { key: "status", header: "Bank status", cell: (r) => <StatusBadge status={bankStatus(r.match_status)} /> },
          ]}
        />
      </Card>
    </>
  )
}
