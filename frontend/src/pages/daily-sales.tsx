import { useMemo, useState, type KeyboardEvent } from "react"
import { CalendarDays, Check, Clock, Info, Lock } from "lucide-react"
import { toast } from "sonner"
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card"
import { Input } from "@/components/ui/input"
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select"
import { Textarea } from "@/components/ui/textarea"
import { useAppState } from "@/components/app/app-state"
import { DataTable } from "@/components/app/data-table"
import { DataState, useNotReady } from "@/components/app/data-state"
import { Field } from "@/components/app/field"
import { StatRow } from "@/components/app/kpi-card"
import { MoneyInput } from "@/components/app/money-input"
import { PageHeader } from "@/components/app/page-header"
import { SearchInput } from "@/components/app/search-input"
import { StatusBadge } from "@/components/app/status-badge"
import type { DailySale } from "@/data/types"
import { monthLabel } from "@/lib/calendar"
import { addDays, daysBetween, fmt, parseAmount, sum } from "@/lib/format"

export default function DailySalesPage() {
  const { dailySales, currentUser, actions, allow, today: TODAY, now: NOW, mode } = useAppState()
  const live = mode === "live"
  const canEdit = allow("sales.enter")
  const notReady = useNotReady(["sales"])
  const [saving, setSaving] = useState(false)
  // months that have entries, newest first
  const MONTHS = [["all", "All months"], ...[...new Set(dailySales.map((s) => s.sales_date.slice(0, 7)))].sort().reverse().map((m) => [m, monthLabel(m + "-01")])]

  // cash is counted the next morning, so the next date to enter is usually the day after the latest entry
  const latest = dailySales[0]?.sales_date
  const nextDate = latest && latest < TODAY ? addDays(latest, 1) : TODAY

  // ---- entry form
  const [date, setDate] = useState(nextDate)
  const [cash, setCash] = useState("")
  const [card, setCard] = useState("")
  const [note, setNote] = useState("")
  const [errors, setErrors] = useState<Record<string, string | undefined>>({})
  const cashV = parseAmount(cash), cardV = parseAmount(card)
  const total = (Number.isNaN(cashV) ? 0 : cashV ?? 0) + (Number.isNaN(cardV) ? 0 : cardV ?? 0)
  const existing = dailySales.find((s) => s.sales_date === date)
  const backdated = !!date && daysBetween(date, TODAY) >= 2
  // the sales service keeps one record per date and has no update yet
  const locked = live && !!existing

  const reset = () => { setCash(""); setCard(""); setNote(""); setErrors({}) }
  const save = async () => {
    const e = {
      date: !date ? "Choose the sales date." : date > TODAY ? "Sales date can't be in the future." : undefined,
      cash: cashV == null ? "Enter cash sales (0 if none)." : Number.isNaN(cashV) || cashV < 0 ? "Enter a positive amount." : undefined,
      card: cardV == null ? "Enter card payments (0 if none)." : Number.isNaN(cardV) || cardV < 0 ? "Enter a positive amount." : undefined,
    }
    setErrors(e)
    if (e.date || e.cash || e.card) return
    setSaving(true)
    const err = await actions.saveSale({
      sales_date: date, cash_sales: cashV!, card_sales: cardV!, total: cashV! + cardV!, entered_at: NOW, entered_by: currentUser?.name ?? "",
      entry_type: backdated ? "Backdated" : "Standard", note,
    })
    setSaving(false)
    if (err) return void toast.error(`Sales for ${fmt.date(date)} were not saved`, { description: err })
    toast.success(`Sales for ${fmt.date(date)} ${existing ? "updated" : "saved"}`, { description: fmt.lkr(cashV! + cardV!) })
    reset()
  }
  const next = (id: string) => (e: KeyboardEvent) => { if (e.key === "Enter") { e.preventDefault(); document.getElementById(id)?.focus() } }

  // ---- list
  const [q, setQ] = useState("")
  const [month, setMonth] = useState("all")
  const [type, setType] = useState("all")
  const rows = useMemo(() => {
    const s = q.trim().toLowerCase()
    return dailySales.filter((r) =>
      (month === "all" || r.sales_date.startsWith(month)) && (type === "all" || r.entry_type === type) &&
      (!s || [r.sales_date, fmt.day(r.sales_date), r.entered_by, r.note, fmt.amt(r.total)].join(" ").toLowerCase().includes(s)))
  }, [dailySales, q, month, type])
  const last7 = dailySales.filter((r) => r.sales_date > addDays(TODAY, -8))

  return (
    <>
      <PageHeader title="Daily Sales" description="Actual cash sales and card payments. Cash is counted the next morning. The sales date is the day the sales happened; the entry time is recorded automatically." />
      {latest && latest < addDays(TODAY, -1) && (
        <Alert variant="info">
          <CalendarDays />
          <AlertTitle>Sales from {fmt.day(addDays(latest, 1))} haven't been entered yet</AlertTitle>
          <AlertDescription>Last entry is for {fmt.day(latest)}.{!live && " Predicted sales are used in the forecast until actual sales are entered."}</AlertDescription>
        </Alert>
      )}
      <div className="grid items-start gap-4 xl:grid-cols-[380px_minmax(0,1fr)]">
        <div className="grid gap-4">
          <Card>
            <CardHeader>
              <CardTitle>New sales entry</CardTitle>
            </CardHeader>
            <CardContent className="grid gap-3.5">
              {!canEdit && (
                <Alert><Lock /><AlertTitle>View only</AlertTitle><AlertDescription>Your role can't enter daily sales.</AlertDescription></Alert>
              )}
              {existing && (
                <Alert variant="info">
                  <Info />
                  <AlertTitle>An entry for {fmt.date(date)} already exists</AlertTitle>
                  <AlertDescription>
                    Recorded {fmt.lkr(existing.total)} on {fmt.dateTimeFull(existing.entered_at)} by {existing.entered_by}.
                    {locked ? " Recorded sales can't be changed yet." : " Saving will replace it."}
                  </AlertDescription>
                </Alert>
              )}
              <Field label="Sales date" htmlFor="ds-date" error={errors.date} help={date ? `${fmt.date(date)} · backdated entries are allowed and marked as backdated` : "Backdated entries are allowed and marked as backdated."}>
                <Input id="ds-date" type="date" max={TODAY} value={date} disabled={!canEdit} onChange={(e) => setDate(e.target.value)} onKeyDown={next("ds-cash")} aria-invalid={!!errors.date || undefined} />
              </Field>
              {backdated && <Badge variant="outline"><Clock />Backdated entry</Badge>}
              <div className="grid gap-3.5 sm:grid-cols-2">
                <Field label="Cash sales (LKR)" htmlFor="ds-cash" error={errors.cash}>
                  <MoneyInput id="ds-cash" value={cash} onChange={setCash} disabled={!canEdit} invalid={!!errors.cash} onKeyDown={next("ds-card")} />
                </Field>
                <Field label="Card payments (LKR)" htmlFor="ds-card" error={errors.card}>
                  <MoneyInput id="ds-card" value={card} onChange={setCard} disabled={!canEdit} invalid={!!errors.card} onKeyDown={next("ds-save")} />
                </Field>
              </div>
              <div className="bg-muted flex justify-between gap-3 rounded-md border px-3.5 py-2.5 text-[13px]">
                <span>Total sales</span>
                <b className="tabular">{fmt.lkr(total)}</b>
              </div>
              {!live && (
                <Field label="Note (optional)" htmlFor="ds-note">
                  <Textarea id="ds-note" rows={2} placeholder="e.g. till report delayed" value={note} disabled={!canEdit} onChange={(e) => setNote(e.target.value)} />
                </Field>
              )}
              <p className="text-muted-foreground flex items-center gap-1.5 text-xs"><Clock className="size-3.5" />Entry timestamp: recorded on save (now {fmt.dateTimeFull(NOW)})</p>
              <div className="flex gap-2">
                <Button id="ds-save" onClick={() => void save()} disabled={!canEdit || locked || saving}><Check />{saving ? "Saving…" : existing && !live ? "Update entry" : "Save entry"}</Button>
                <Button variant="outline" onClick={reset} disabled={!canEdit}>Clear</Button>
              </div>
            </CardContent>
          </Card>
          <Card>
            <CardHeader>
              <CardTitle>Last 7 entered days</CardTitle>
              <CardDescription>Sales by sales date</CardDescription>
            </CardHeader>
            <CardContent>
              <StatRow items={[
                { label: "Cash", value: fmt.compact(sum(last7, (r) => r.cash_sales)) },
                { label: "Card", value: fmt.compact(sum(last7, (r) => r.card_sales)) },
                { label: "Daily avg", value: fmt.compact(last7.length ? sum(last7, (r) => r.total) / last7.length : 0) },
              ]} />
            </CardContent>
          </Card>
        </div>
        {notReady ? <DataState resources={["sales"]} what="Sales entries" /> : <Card>
          <CardHeader>
            <CardTitle>Sales entries</CardTitle>
            <CardDescription>Sales date and entry time are shown separately</CardDescription>
          </CardHeader>
          <CardContent className="flex flex-wrap gap-2">
            <SearchInput value={q} onChange={setQ} placeholder="Search date, amount, person or note" />
            <Select value={month} onValueChange={setMonth}>
              <SelectTrigger aria-label="Month" className="w-44"><SelectValue /></SelectTrigger>
              <SelectContent>{MONTHS.map(([v, l]) => <SelectItem key={v} value={v}>{l}</SelectItem>)}</SelectContent>
            </Select>
            <Select value={type} onValueChange={setType}>
              <SelectTrigger aria-label="Entry type" className="w-40"><SelectValue /></SelectTrigger>
              <SelectContent>
                <SelectItem value="all">All entries</SelectItem>
                <SelectItem value="Backdated">Backdated only</SelectItem>
              </SelectContent>
            </Select>
          </CardContent>
          <DataTable<DailySale>
            rows={rows}
            rowKey={(r) => r.sales_date}
            defaultSort={{ key: "date", dir: "desc" }}
            maxHeight={640}
            footer={(rs) => <><span>{rs.length} entries</span><span className="tabular">Total {fmt.lkr(sum(rs, (r) => r.total))}</span></>}
            columns={[
              { key: "date", header: "Sales date", sortValue: (r) => r.sales_date, cell: (r) => <span className="font-semibold whitespace-nowrap">{fmt.day(r.sales_date)}</span> },
              { key: "cash", header: "Cash (LKR)", numeric: true, sortValue: (r) => r.cash_sales, cell: (r) => fmt.amt(r.cash_sales) },
              { key: "card", header: "Card (LKR)", numeric: true, sortValue: (r) => r.card_sales, cell: (r) => fmt.amt(r.card_sales) },
              { key: "total", header: "Total (LKR)", numeric: true, sortValue: (r) => r.total, cell: (r) => <b className="font-semibold">{fmt.amt(r.total)}</b> },
              { key: "entered", header: "Entry timestamp", sortValue: (r) => r.entered_at, cell: (r) => <span className="text-muted-foreground whitespace-nowrap">{fmt.dateTime(r.entered_at)}</span> },
              { key: "by", header: "Entered by", sortValue: (r) => r.entered_by, cell: (r) => r.entered_by },
              { key: "type", header: "Type", sortValue: (r) => r.entry_type, cell: (r) => (r.entry_type === "Backdated" ? <StatusBadge status="Backdated" /> : <span className="text-muted-foreground">On time</span>) },
              ...(live ? [] : [{ key: "note", header: "Note", cell: (r: DailySale) => <span className="text-muted-foreground">{r.note}</span> }]),
            ]}
            empty="No sales entered yet."
          />
        </Card>}
      </div>
    </>
  )
}
