import { useEffect, useMemo, useState } from "react"
import { useSearchParams } from "react-router-dom"
import { Info, Plus } from "lucide-react"
import { toast } from "sonner"
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert"
import { Button } from "@/components/ui/button"
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card"
import { Input } from "@/components/ui/input"
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select"
import { useAppState } from "@/components/app/app-state"
import { CancelChequeDialog, ChequeFormDialog } from "@/components/app/cheque-form-dialog"
import { CellStack, DataTable } from "@/components/app/data-table"
import { DataState, useNotReady } from "@/components/app/data-state"
import { StatRow } from "@/components/app/kpi-card"
import { PageHeader } from "@/components/app/page-header"
import { SearchInput } from "@/components/app/search-input"
import { StatusBadge } from "@/components/app/status-badge"
import type { Cheque } from "@/data/types"
import { CHEQUE_CATEGORIES, chequeCategoryText, isPastClearingDate, isUpcoming, paymentState } from "@/lib/cheques"
import { fmt, sum } from "@/lib/format"

/**
 * A cheque is a future payment, forecast on its expected clearing date. There is no bank-based clearing or bounce
 * detection yet, so a cheque is never marked paid: past its clearing date it stays Scheduled and is shown as
 * "Past clearing date". Bounced exists only in the demonstration data.
 */
type Show = "upcoming" | "past" | "Cancelled" | "Bounced" | "all"
const SHOW: [Show, string][] = [["upcoming", "Upcoming payments"], ["past", "Past clearing date"], ["Cancelled", "Cancelled"], ["Bounced", "Bounced"], ["all", "All cheques"]]

export default function ChequesPage() {
  const { cheques, kpis, allow, today, mode, forecastInfo } = useAppState()
  const notReady = useNotReady(["cheques"])
  const [params, setParams] = useSearchParams()
  const [addOpen, setAddOpen] = useState(false)
  const [editing, setEditing] = useState<Cheque | null>(null)
  const [cancelling, setCancelling] = useState<Cheque | null>(null)
  const [show, setShow] = useState<Show>("upcoming")
  const [cat, setCat] = useState("all")
  const [q, setQ] = useState("")
  const [from, setFrom] = useState("")
  const [to, setTo] = useState("")
  const canEdit = allow("cheques.manage")

  useEffect(() => {
    if (params.get("new") === "1" && canEdit) { setAddOpen(true); setParams({}, { replace: true }) }
  }, [params, setParams, canEdit])

  const rows = useMemo(() => {
    const s = q.trim().toLowerCase()
    return cheques.filter((c) =>
      (show === "all" || (show === "upcoming" ? isUpcoming(c, today) : show === "past" ? isPastClearingDate(c, today) : c.status === show)) &&
      (cat === "all" || c.category === cat) && (!from || c.expected_clear_date >= from) && (!to || c.expected_clear_date <= to) &&
      (!s || [c.cheque_no, c.payee, c.notes, c.other_description ?? "", fmt.amt(c.amount)].join(" ").toLowerCase().includes(s)))
  }, [cheques, show, cat, q, from, to, today])

  return (
    <>
      <PageHeader
        title="Cheque Payments"
        description="Future company payments by cheque. Each one is forecast on its expected clearing date — expenses are never predicted automatically."
        actions={canEdit && <Button onClick={() => setAddOpen(true)}><Plus />Add cheque payment</Button>}
      />
      <Alert variant="info">
        <Info />
        <AlertTitle>Forecast on the expected clearing date</AlertTitle>
        <AlertDescription>
          The forecast deducts each scheduled cheque on its expected clearing date only. FlowLedger never marks a cheque paid: after that
          date it stays Scheduled, shown as past its clearing date. Recording a cheque never changes the Actual Bank Balance.
        </AlertDescription>
      </Alert>
      <StatRow items={[
        { label: "Upcoming cheque payments", value: kpis.upcomingCount, sub: fmt.lkr0(kpis.upcomingAmount) },
        { label: "Clearing in the next 7 days", value: kpis.cheques7dCount, sub: fmt.lkr0(kpis.cheques7dAmount) },
        mode === "live"
          ? { label: "Expected cheque outflows · forecast", value: fmt.lkr0(kpis.forecastOutflow), sub: forecastInfo ? `${fmt.date(forecastInfo.run.horizonStartDate)} – ${fmt.date(forecastInfo.run.horizonEndDate)}` : "No forecast yet" }
          : { label: "Expected cheque outflows · 12 weeks", value: fmt.lkr0(kpis.forecastOutflow), sub: "Deducted on expected clearing dates" },
      ]} />
      <DataState resources={["cheques"]} what="Cheque payments" />
      {!notReady && (
      <Card>
        <CardHeader>
          <CardTitle>Cheques</CardTitle>
          <CardDescription>Upcoming payments are deducted in the forecast on their expected clearing date. Cancelled cheques are not counted.</CardDescription>
        </CardHeader>
        <CardContent className="flex flex-wrap items-center gap-2">
          <SearchInput value={q} onChange={setQ} placeholder="Search payee, cheque no. or note" />
          <Select value={show} onValueChange={(v) => setShow(v as Show)}>
            <SelectTrigger aria-label="Show" className="w-52"><SelectValue /></SelectTrigger>
            <SelectContent>{SHOW.filter(([v]) => mode === "demo" || v !== "Bounced").map(([v, l]) => <SelectItem key={v} value={v}>{l}</SelectItem>)}</SelectContent>
          </Select>
          <Select value={cat} onValueChange={setCat}>
            <SelectTrigger aria-label="Category" className="w-44"><SelectValue /></SelectTrigger>
            <SelectContent>
              <SelectItem value="all">All categories</SelectItem>
              {CHEQUE_CATEGORIES.map((c) => <SelectItem key={c} value={c}>{c}</SelectItem>)}
            </SelectContent>
          </Select>
          <span className="text-muted-foreground text-xs">Expected clearing</span>
          <Input type="date" className="w-40" value={from} onChange={(e) => setFrom(e.target.value)} aria-label="Expected clearing from" />
          <span className="text-muted-foreground">–</span>
          <Input type="date" className="w-40" value={to} onChange={(e) => setTo(e.target.value)} aria-label="Expected clearing to" />
          <Button variant="ghost" size="sm" onClick={() => { setShow("upcoming"); setCat("all"); setFrom(""); setTo(""); setQ("") }}>Reset</Button>
        </CardContent>
        <DataTable<Cheque>
          rows={rows}
          rowKey={(r) => r.cheque_no}
          defaultSort={{ key: "clears", dir: "asc" }}
          maxHeight={620}
          empty={cheques.length ? "No cheques match these filters." : "No cheque payments recorded yet."}
          footer={(rs) => <><span>{rs.length} cheques</span><span className="tabular">Total {fmt.lkr(sum(rs, (r) => r.amount))}</span></>}
          columns={[
            { key: "no", header: "Cheque no.", sortValue: (r) => r.cheque_no, cell: (r) => <span className="font-mono text-xs">{r.cheque_no}</span> },
            { key: "payee", header: "Payee", sortValue: (r) => r.payee, cell: (r) => <CellStack className="min-w-44" main={r.payee} sub={r.notes} /> },
            { key: "cat", header: "Category", sortValue: (r) => chequeCategoryText(r), cell: (r) => <span className="whitespace-nowrap">{chequeCategoryText(r)}</span> },
            { key: "amount", header: "Amount (LKR)", numeric: true, sortValue: (r) => r.amount, cell: (r) => <b className="font-semibold">{fmt.amt(r.amount)}</b> },
            { key: "date", header: "Issue date", sortValue: (r) => r.cheque_date, cell: (r) => <span className="whitespace-nowrap">{fmt.short(r.cheque_date)}</span> },
            { key: "clears", header: "Expected clearing", sortValue: (r) => r.expected_clear_date, cell: (r) => <span className="whitespace-nowrap">{fmt.day(r.expected_clear_date)}</span> },
            {
              key: "status", header: "Payment", sortValue: (r) => paymentState(r, today), cell: (r) => (
                <CellStack main={<StatusBadge status={paymentState(r, today)} />} sub={isUpcoming(r, today) ? `Forecast on ${fmt.short(r.expected_clear_date)}` : isPastClearingDate(r, today) ? "Not confirmed paid" : undefined} />
              ),
            },
            {
              key: "act", header: "", cell: (r) => (canEdit && isUpcoming(r, today) ? (
                <div className="flex gap-1.5">
                  <Button variant="outline" size="sm" onClick={() => setEditing(r)}>Edit</Button>
                  <Button variant="outline" size="sm" onClick={() => setCancelling(r)}>Cancel</Button>
                </div>
              ) : null),
            },
          ]}
        />
      </Card>
      )}
      <ChequeFormDialog open={addOpen} onOpenChange={setAddOpen} defaultDate={today}
        onSaved={(c) => toast.success(`Cheque ${c.cheque_no} added`, { description: `${fmt.lkr(c.amount)} · forecast on ${fmt.day(c.expected_clear_date)}` })} />
      <ChequeFormDialog open={!!editing} onOpenChange={(o) => !o && setEditing(null)} cheque={editing} defaultDate={today}
        onSaved={(c) => toast.success(`Cheque ${c.cheque_no} updated`, { description: `Forecast on ${fmt.day(c.expected_clear_date)}` })} />
      <CancelChequeDialog cheque={cancelling} onClose={() => setCancelling(null)} onCancelled={(c) => toast(`Cheque ${c.cheque_no} cancelled`, { description: "Removed from the forecast" })} />
    </>
  )
}
