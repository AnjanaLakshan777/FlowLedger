import { useEffect, useMemo, useRef, useState, type KeyboardEvent } from "react"
import {
  AlertTriangle, Banknote, Check, CheckCircle2, ChevronLeft, ChevronRight, CreditCard, FileText, Pencil, Plus, TrendingUp, XCircle, type LucideIcon,
} from "lucide-react"
import { toast } from "sonner"
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { TODAY } from "@/data"
import type { Cheque } from "@/data/types"
import {
  buildCalendar, canStep, describeDay, lkDate, monthGrid, monthLabel, monthStart, navigate, weekdayLabel, weekStart,
  type CalendarDay, type CalendarEvent, type CalendarMode, type CalendarNav, type EventKind, type NavAction,
} from "@/lib/calendar"
import { isUpcoming } from "@/lib/forecast"
import { addDays, fmt } from "@/lib/format"
import { cn } from "@/lib/utils"
import { useAppState } from "./app-state"
import { CancelChequeDialog, ChequeFormDialog } from "./cheque-form-dialog"
import { StatRow } from "./kpi-card"
import { Segmented } from "./segmented"
import { StatusBadge } from "./status-badge"

interface Indicator { key: string; icon: LucideIcon; label: string; color: string; dot: string }
const IND: Record<string, Indicator> = {
  cash: { key: "cash", icon: Banknote, label: "Expected cash deposit", color: "text-chart-2", dot: "bg-chart-2" },
  card: { key: "card", icon: CreditCard, label: "Expected card settlement", color: "text-chart-4", dot: "bg-chart-4" },
  cheque: { key: "cheque", icon: FileText, label: "Scheduled cheque payment", color: "text-muted-foreground", dot: "bg-muted-foreground" },
  bank: { key: "bank", icon: CheckCircle2, label: "Confirmed bank transaction", color: "text-primary dark:text-chart-2", dot: "bg-primary" },
  shortage: { key: "shortage", icon: AlertTriangle, label: "Cash Shortage", color: "text-destructive", dot: "bg-destructive" },
}
const EVENT_ICON: Record<EventKind, LucideIcon> = { cash: Banknote, card: CreditCard, cheque: FileText, bank: CheckCircle2 }
const EVENT_STATUS = { actual: "Confirmed", excluded: "Excluded", expected: "Expected" } as const

/** At most one icon per kind, so a day cell never gets crowded. */
function indicators(day?: CalendarDay): Indicator[] {
  if (!day) return []
  const has = (k: EventKind) => day.events.some((e) => e.kind === k && e.status === "expected")
  return [
    has("cash") && IND.cash,
    has("card") && IND.card,
    has("cheque") && IND.cheque,
    day.events.some((e) => e.status === "actual") && IND.bank,
    day.basis === "forecast" && day.closing < 0 && IND.shortage,
  ].filter(Boolean) as Indicator[]
}

/**
 * Calendar-first view of the cash-flow forecast. Days up to today show bank data (Actual Bank Balance); later days show
 * the forecast. Cheques are deducted on their expected bank clearing date, never on the cheque date.
 */
export function CashFlowCalendar({ initialSelected = TODAY, initialMode = "month" }: { initialSelected?: string; initialMode?: CalendarMode }) {
  const { forecast, transactions, kpis, cheques, allow } = useAppState()
  const canEdit = allow("cheques.manage")
  const cal = useMemo(
    () => buildCalendar({ today: TODAY, actualBalance: kpis.actualBalance, transactions, forecastDays: forecast.days }),
    [forecast.days, transactions, kpis.actualBalance]
  )
  const [nav, setNav] = useState<CalendarNav>({ mode: initialMode, anchor: initialSelected, selected: initialSelected })
  const [focusDate, setFocusDate] = useState<string | null>(null)
  const [form, setForm] = useState<{ open: boolean; cheque: Cheque | null }>({ open: false, cheque: null })
  const [cancelling, setCancelling] = useState<Cheque | null>(null)
  const refs = useRef(new Map<string, HTMLButtonElement>())

  const go = (a: NavAction, focus = false) => {
    const next = navigate(nav, a, cal.first, cal.last)
    setNav(next)
    if (focus) setFocusDate(next.selected)
  }
  useEffect(() => {
    if (focusDate) { refs.current.get(focusDate)?.focus(); setFocusDate(null) }
  }, [focusDate, nav])
  const onKey = (e: KeyboardEvent, d: string) => {
    const month = nav.mode === "month"
    const delta: Record<string, number> = { ArrowLeft: -1, ArrowRight: 1, ArrowUp: month ? -7 : -1, ArrowDown: month ? 7 : 1, PageUp: month ? -28 : -7, PageDown: month ? 28 : 7 }
    let target: string | null = null
    if (e.key in delta) target = addDays(d, delta[e.key])
    else if (e.key === "Home") target = weekStart(d)
    else if (e.key === "End") target = addDays(weekStart(d), 6)
    if (target) { e.preventDefault(); go({ type: "select", date: target }, true) }
  }
  const cellRef = (d: string) => (el: HTMLButtonElement | null) => { if (el) refs.current.set(d, el); else refs.current.delete(d) }

  const weekDays = Array.from({ length: 7 }, (_, i) => addDays(weekStart(nav.anchor), i))
  const title = nav.mode === "month" ? monthLabel(nav.anchor) : `${lkDate(weekDays[0])} – ${lkDate(weekDays[6])}`
  const day = cal.days.get(nav.selected)
  const onSaved = (c: Cheque, verb: string) => {
    const on = c.expected_clear_date
    toast.success(`Cheque #${c.cheque_no} ${verb}`, {
      description: `${fmt.lkr(c.amount)} forecast on ${lkDate(on)}, its expected clearing date`,
      action: on !== nav.selected ? { label: "View date", onClick: () => go({ type: "select", date: on }) } : undefined,
    })
  }

  return (
    <section aria-label="Cash flow calendar" className="bg-card grid gap-4 rounded-xl border p-3 shadow-xs sm:p-4">
      <div className="flex flex-wrap items-center gap-2">
        <Button variant="outline" size="icon" onClick={() => go({ type: "step", n: -1 })} disabled={!canStep(nav, -1, cal.first, cal.last)} aria-label={nav.mode === "month" ? "Previous month" : "Previous week"}><ChevronLeft /></Button>
        <Button variant="outline" size="icon" onClick={() => go({ type: "step", n: 1 })} disabled={!canStep(nav, 1, cal.first, cal.last)} aria-label={nav.mode === "month" ? "Next month" : "Next week"}><ChevronRight /></Button>
        <Button variant="outline" onClick={() => go({ type: "today", today: TODAY }, true)}>Today</Button>
        <h2 className="min-w-0 flex-1 truncate text-lg font-semibold tracking-tight" aria-live="polite">{title}</h2>
        <Segmented value={nav.mode} onChange={(m) => go({ type: "mode", mode: m })} options={[{ value: "month", label: "Month" }, { value: "week", label: "Week" }]} />
      </div>
      <Legend />
      <div className="grid items-start gap-4 xl:grid-cols-[minmax(0,1fr)_380px]">
        {nav.mode === "month"
          ? <MonthGrid anchor={nav.anchor} cal={cal} selected={nav.selected} onSelect={(d) => go({ type: "select", date: d })} onKey={onKey} cellRef={cellRef} />
          : <WeekView days={weekDays} cal={cal} selected={nav.selected} onSelect={(d) => go({ type: "select", date: d })} onKey={onKey} cellRef={cellRef} />}
        <DayDetail
          iso={nav.selected} day={day} cheques={cheques} canEdit={canEdit}
          onAdd={() => setForm({ open: true, cheque: null })}
          onEdit={(c) => setForm({ open: true, cheque: c })}
          onCancel={setCancelling}
          onGoTo={(d) => go({ type: "select", date: d })}
        />
      </div>
      <ChequeFormDialog
        open={form.open} onOpenChange={(o) => setForm((s) => ({ ...s, open: o }))} cheque={form.cheque} defaultDate={nav.selected}
        onSaved={(c) => onSaved(c, form.cheque ? "updated" : "added")}
      />
      <CancelChequeDialog cheque={cancelling} onClose={() => setCancelling(null)} onCancelled={(c) => toast(`Cheque #${c.cheque_no} cancelled`, { description: "Removed from the forecast" })} />
    </section>
  )
}

function Legend() {
  return (
    <ul className="text-muted-foreground flex flex-wrap gap-x-4 gap-y-1 text-xs" aria-label="Calendar legend">
      {Object.values(IND).map((i) => (
        <li key={i.key} className="inline-flex items-center gap-1.5"><i.icon className={cn("size-3.5", i.color)} aria-hidden />{i.label}</li>
      ))}
      <li className="inline-flex items-center gap-1.5"><span className="bg-card size-3 rounded-[3px] border" aria-hidden />Actual (bank data)</li>
      <li className="inline-flex items-center gap-1.5"><span className="size-3 rounded-[3px] border border-dashed" aria-hidden />Forecast</li>
    </ul>
  )
}

interface ViewProps {
  cal: ReturnType<typeof buildCalendar>; selected: string
  onSelect: (d: string) => void; onKey: (e: KeyboardEvent, d: string) => void; cellRef: (d: string) => (el: HTMLButtonElement | null) => void
}

/** One decimal ("12.5M") so amounts fit phone-width cells. */
const tiny = (v: number) => (v >= 1e6 ? (v / 1e6).toFixed(1) + "M" : v >= 1e3 ? Math.round(v / 1e3) + "K" : String(Math.round(v)))
/** Phone cells get the shorter figure; larger screens the usual compact one. */
const Amount = ({ v }: { v: number }) => <><span className="sm:hidden">{tiny(v)}</span><span className="hidden sm:inline">{fmt.compact(v)}</span></>

/** Closing figure for a cell: below zero it is a shortage, never a negative balance. */
function CellBalance({ day, className, withLabel }: { day?: CalendarDay; className?: string; withLabel?: boolean }) {
  if (!day) return null
  if (day.closing < 0) {
    if (withLabel) {
      return (
        <span className={cn("text-destructive grid min-w-0 font-semibold", className)}>
          <span className="text-[11px] font-medium">Cash Shortage</span><span className="truncate"><Amount v={-day.closing} /></span>
        </span>
      )
    }
    return (
      <span className={cn("text-destructive flex min-w-0 items-center gap-0.5 font-semibold", className)}>
        <AlertTriangle className="hidden size-3.5 shrink-0 sm:inline" aria-hidden />
        <span className="truncate"><Amount v={-day.closing} /></span>
      </span>
    )
  }
  return <span className={cn("font-semibold", className)}><Amount v={day.closing} /></span>
}

const cellClass = (day: CalendarDay | undefined, selected: boolean) => cn(
  "focus-visible:ring-ring/50 w-full rounded-md border text-left outline-none focus-visible:ring-[3px]",
  day?.basis === "forecast" ? "border-dashed" : "bg-card",
  day && day.basis === "forecast" && day.closing < 0 && "border-destructive/60 bg-destructive/10",
  !day && "text-muted-foreground/40 cursor-default border-transparent bg-transparent",
  selected && "border-primary ring-primary border-solid ring-2",
  day && !selected && "hover:border-ring",
)

function MonthGrid({ anchor, cal, selected, onSelect, onKey, cellRef }: ViewProps & { anchor: string }) {
  const rows = monthGrid(anchor)
  const month = monthStart(anchor).slice(0, 7)
  return (
    <table className="w-full table-fixed border-separate border-spacing-1 sm:border-spacing-1.5" aria-label={monthLabel(anchor)}>
      <thead>
        <tr>
          {rows[0].map((d) => (
            <th key={d} scope="col" className="text-muted-foreground pb-1 text-center text-xs font-medium sm:text-[13px]">
              <abbr title={weekdayLabel(d, "long")} className="no-underline">{weekdayLabel(d)}</abbr>
            </th>
          ))}
        </tr>
      </thead>
      <tbody>
        {rows.map((row) => (
          <tr key={row[0]}>
            {row.map((d) => {
              const day = cal.days.get(d)
              const ind = indicators(day)
              return (
                <td key={d} className="p-0 align-top">
                  <button
                    ref={cellRef(d)} type="button" disabled={!day}
                    tabIndex={d === selected ? 0 : -1} aria-pressed={d === selected} aria-current={d === TODAY ? "date" : undefined} aria-label={describeDay(day, d)}
                    onClick={() => onSelect(d)} onKeyDown={(e) => onKey(e, d)}
                    className={cn(cellClass(day, d === selected), "grid h-16 content-between p-1 sm:h-24 sm:p-2 lg:h-28", !d.startsWith(month) && "opacity-45")}
                  >
                    <span className="flex items-center justify-between gap-1">
                      <span className={cn("text-xs font-medium tabular sm:text-sm", d === TODAY && "bg-primary text-primary-foreground grid size-5 place-items-center rounded-full sm:size-6")}>
                        {Number(d.slice(8))}
                      </span>
                      {day?.basis === "actual"
                        ? <span className="text-muted-foreground hidden text-[10px] font-medium uppercase lg:inline">Actual</span>
                        : null}
                    </span>
                    <CellBalance day={day} className="truncate text-[10px] tabular sm:text-sm lg:text-[15px]" />
                    {ind.length > 0 ? (
                      <>
                        <span className="hidden items-center gap-1 sm:flex" aria-hidden>{ind.map((i) => <i.icon key={i.key} className={cn("size-3.5", i.color)} />)}</span>
                        <span className="flex flex-wrap gap-0.5 sm:hidden" aria-hidden>{ind.map((i) => <span key={i.key} className={cn("size-1.5 rounded-full", i.dot)} />)}</span>
                      </>
                    ) : <span className="h-1.5 sm:h-3.5" />}
                  </button>
                </td>
              )
            })}
          </tr>
        ))}
      </tbody>
    </table>
  )
}

function WeekView({ days, cal, selected, onSelect, onKey, cellRef }: ViewProps & { days: string[] }) {
  const recs = days.map((d) => cal.days.get(d)).filter(Boolean) as CalendarDay[]
  const actual = recs.filter((r) => r.basis === "actual")
  const fc = recs.filter((r) => r.basis === "forecast")
  const first = recs[0], last = recs.at(-1)
  const total = (rs: CalendarDay[], k: "receipts" | "outflows") => rs.reduce((t, r) => t + r[k], 0)
  return (
    <div className="grid gap-3">
      {first && last && (
        <StatRow items={[
          first.opening < 0
            ? { label: "Cash Shortage carried in", value: fmt.compact(-first.opening), negative: true }
            : { label: first.basis === "actual" ? "Opening Actual Bank Balance" : "Expected opening balance", value: fmt.compact(first.opening) },
          ...(actual.length ? [{ label: "Confirmed receipts", value: fmt.compact(total(actual, "receipts")) }, { label: "Confirmed payments", value: fmt.compact(total(actual, "outflows")) }] : []),
          ...(fc.length ? [{ label: "Expected receipts", value: fmt.compact(total(fc, "receipts")) }, { label: "Expected cheque outflows", value: fmt.compact(total(fc, "outflows")) }] : []),
          last.closing < 0
            ? { label: "Cash Shortage at week end", value: fmt.compact(-last.closing), negative: true }
            : { label: last.basis === "actual" ? "Closing Actual Bank Balance" : "Expected closing balance", value: fmt.compact(last.closing) },
        ]} />
      )}
      <ul className="grid gap-2 sm:grid-cols-2 lg:grid-cols-7" aria-label="Week">
        {days.map((d) => {
          const day = cal.days.get(d)
          const ind = indicators(day)
          const shown = day?.events.slice(0, 4) ?? []
          return (
            <li key={d} className="min-w-0">
              <button
                ref={cellRef(d)} type="button" disabled={!day}
                tabIndex={d === selected ? 0 : -1} aria-pressed={d === selected} aria-current={d === TODAY ? "date" : undefined} aria-label={describeDay(day, d)}
                onClick={() => onSelect(d)} onKeyDown={(e) => onKey(e, d)}
                className={cn(cellClass(day, d === selected), "grid h-full content-start gap-1.5 p-2.5 text-xs lg:min-h-52")}
              >
                <span className="flex items-baseline justify-between gap-1">
                  <b className="text-[13px] font-semibold">{weekdayLabel(d)}</b>
                  <span className={cn("text-muted-foreground tabular", d === TODAY && "text-primary dark:text-chart-2 font-semibold")}>{d === TODAY ? "Today" : lkDate(d)}</span>
                </span>
                {day ? (
                  <>
                    <CellBalance day={day} withLabel className="text-[15px] tabular" />
                    <span className="text-muted-foreground tabular" title={day.basis === "actual" ? "Confirmed receipts · Confirmed payments" : "Expected receipts · Expected cheque outflows"}>In {fmt.compact(day.receipts)} · Out {fmt.compact(day.outflows)}</span>
                    {ind.length > 0 && <span className="flex gap-1" aria-hidden>{ind.map((i) => <i.icon key={i.key} className={cn("size-3.5", i.color)} />)}</span>}
                    {shown.map((e) => <span key={e.id} className="text-muted-foreground truncate">{shortTitle(e)}</span>)}
                    {day.events.length > shown.length && <span className="text-muted-foreground">+{day.events.length - shown.length} more</span>}
                  </>
                ) : <span>No data</span>}
              </button>
            </li>
          )
        })}
      </ul>
    </div>
  )
}

const shortTitle = (e: CalendarEvent) =>
  e.kind === "cheque" && e.status === "expected" ? e.title.replace(/^Cheque #\d+ · /, "Chq ") : e.title.replace(/^Expected /, "")

function DayDetail({ iso, day, cheques, canEdit, onAdd, onEdit, onCancel, onGoTo }: {
  iso: string; day?: CalendarDay; cheques: Cheque[]; canEdit: boolean
  onAdd: () => void; onEdit: (c: Cheque) => void; onCancel: (c: Cheque) => void; onGoTo: (d: string) => void
}) {
  const heading = `${weekdayLabel(iso, "long")} ${lkDate(iso)}`
  const byNo = new Map(cheques.map((c) => [c.cheque_no, c]))
  // written on this date but deducted on another (their clearing date) — shown for context, not counted here
  const writtenHere = cheques.filter((c) => isUpcoming(c, TODAY) && c.cheque_date === iso && c.expected_clear_date !== iso)
  const addButton = canEdit && <Button size="sm" onClick={onAdd}><Plus />Add Cheque Payment</Button>

  if (!day) {
    return (
      <aside aria-label={`Details for ${heading}`} className="grid content-start gap-3 rounded-lg border p-4 text-sm">
        <h3 className="text-[15px] font-semibold">{heading}</h3>
        <p className="text-muted-foreground">No data for this date.</p>
      </aside>
    )
  }
  const actual = day.basis === "actual"
  const items = [
    day.opening < 0
      ? { label: "Cash Shortage carried in", value: fmt.lkr0(-day.opening), negative: true }
      : { label: actual ? "Opening Actual Bank Balance" : "Expected opening balance", value: fmt.lkr0(day.opening) },
    { label: actual ? "Confirmed receipts" : "Expected receipts", value: fmt.lkr0(day.receipts) },
    { label: actual ? "Confirmed payments" : "Expected cheque outflows", value: fmt.lkr0(day.outflows) },
    // a negative closing is shown once, as the shortage warning — not also as a negative balance
    ...(day.closing >= 0 ? [{ label: actual ? "Closing Actual Bank Balance" : "Expected closing balance", value: fmt.lkr0(day.closing) }] : []),
  ]
  const chequeActions = (c: Cheque | undefined) => canEdit && c && isUpcoming(c, TODAY) && (
    <span className="flex gap-1">
      <Button variant="ghost" size="icon-sm" onClick={() => onEdit(c)} aria-label={`Edit cheque ${c.cheque_no}`}><Pencil /></Button>
      <Button variant="ghost" size="icon-sm" onClick={() => onCancel(c)} aria-label={`Cancel cheque ${c.cheque_no}`}><XCircle /></Button>
    </span>
  )

  return (
    <aside aria-label={`Details for ${heading}`} className="grid content-start gap-3 rounded-lg border p-4 xl:sticky xl:top-20">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <h3 className="text-[15px] font-semibold">{heading}{iso === TODAY && <span className="text-muted-foreground font-normal"> · Today</span>}</h3>
        {actual ? <Badge variant="soft"><Check />Actual</Badge> : <Badge variant="dashed"><TrendingUp />Forecast</Badge>}
      </div>
      <StatRow items={items} />
      {day.closing < 0 && (
        <Alert variant="destructive">
          <AlertTriangle />
          <AlertTitle>Cash Shortage: {fmt.lkr0(-day.closing)}</AlertTitle>
          <AlertDescription>The projected balance is below zero at the end of this day. The payments below contribute to it.</AlertDescription>
        </Alert>
      )}
      {!actual && <p className="text-muted-foreground -mt-1 text-xs">Forecast figures — not confirmed by the bank.</p>}
      {addButton && <div>{addButton}</div>}

      <div>
        <h4 className="text-muted-foreground mb-1 text-xs font-semibold">{actual ? "Bank transactions" : "Expected receipts and cheque outflows"}</h4>
        {day.events.length === 0 ? (
          <p className="text-muted-foreground text-[13px]">{actual ? "No bank transactions on this day." : "Nothing expected on this day."}</p>
        ) : (
          <ul className="grid max-h-96 overflow-y-auto pr-2">
            {day.events.map((e) => {
              const Icon = EVENT_ICON[e.kind]
              const chq = e.chequeNo ? byNo.get(e.chequeNo) : undefined
              return (
                <li key={e.id} className="grid grid-cols-[16px_minmax(0,1fr)_auto] items-start gap-x-2.5 gap-y-0.5 border-t py-2 text-[13px] first:border-t-0">
                  <Icon className={cn("mt-0.5 size-4", e.kind === "cash" ? IND.cash.color : e.kind === "card" ? IND.card.color : "text-muted-foreground")} aria-hidden />
                  <span className="min-w-0">
                    <span className="block truncate font-medium">{e.title}</span>
                    <span className="text-muted-foreground block text-xs">{e.detail}</span>
                  </span>
                  <span className="grid justify-items-end gap-1">
                    <span className={cn("tabular whitespace-nowrap", e.status === "excluded" && "text-muted-foreground line-through")}>{fmt.signed(e.amount)}</span>
                    <StatusBadge status={EVENT_STATUS[e.status]} />
                    {e.status === "expected" && e.kind === "cheque" && chequeActions(chq)}
                  </span>
                </li>
              )
            })}
          </ul>
        )}
        {actual && day.events.some((e) => e.status !== "actual") && (
          <p className="text-muted-foreground mt-1 text-xs">Excluded duplicates are not part of the Actual Bank Balance.</p>
        )}
      </div>

      {writtenHere.length > 0 && (
        <div>
          <h4 className="text-muted-foreground mb-1 text-xs font-semibold">Cheques issued this day — deducted on their expected clearing date</h4>
          <ul className="grid">
            {writtenHere.map((c) => (
              <li key={c.cheque_no} className="grid grid-cols-[minmax(0,1fr)_auto] items-start gap-x-2.5 border-t py-2 text-[13px] first:border-t-0">
                <span className="min-w-0">
                  <span className="block truncate font-medium">Cheque #{c.cheque_no} · {c.payee}</span>
                  <span className="text-muted-foreground block text-xs">
                    Deducted on{" "}
                    <button type="button" className="hover:text-foreground underline underline-offset-2" onClick={() => onGoTo(c.expected_clear_date)}>{lkDate(c.expected_clear_date)}</button>
                    {" "}· not counted on this day
                  </span>
                </span>
                <span className="grid justify-items-end gap-1">
                  <span className="tabular whitespace-nowrap">{fmt.amt(c.amount)}</span>
                  {chequeActions(c)}
                </span>
              </li>
            ))}
          </ul>
        </div>
      )}
    </aside>
  )
}
