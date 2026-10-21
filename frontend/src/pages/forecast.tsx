import { useState } from "react"
import { AlertTriangle, Check, Info, RefreshCw } from "lucide-react"
import { toast } from "sonner"
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card"
import { Checkbox } from "@/components/ui/checkbox"
import { Label } from "@/components/ui/label"
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select"
import { useAppState, useAutoForecast } from "@/components/app/app-state"
import { CellStack, DataTable } from "@/components/app/data-table"
import { CashFlowCalendar } from "@/components/app/cash-flow-calendar"
import { DataState, useNotReady } from "@/components/app/data-state"
import { balanceLabel, ForecastBalance } from "@/components/app/forecast-balance"
import { StatRow } from "@/components/app/kpi-card"
import { PageHeader } from "@/components/app/page-header"
import { Segmented } from "@/components/app/segmented"
import { ShortageAlerts } from "@/components/app/shortage-alerts"
import { BalanceChart } from "@/components/charts/balance-chart"
import { Legend } from "@/components/charts/chart-tooltip"
import type { ForecastDay, ForecastWeek } from "@/data/types"
import { colomboLocal } from "@/lib/api/adapters"
import type { ForecastWarning } from "@/lib/api/flowledger"
import { FORECAST_WEEKS } from "@/lib/forecast"
import { fmt, sum } from "@/lib/format"

/** Long numeric headers wrap onto two lines, so the 12-week table keeps every column in view. */
const wrapHead = "whitespace-normal leading-tight"
const shortageRow = "[&>td:first-child]:shadow-[inset_3px_0_0_var(--destructive)]"
const range = (r: { low: number; high: number }) => <span className="text-muted-foreground">{fmt.compact(r.low)} – {fmt.compact(r.high)}</span>

export default function ForecastPage() {
  const { forecast, kpis, mode, forecastInfo, forecastAutoUpdate: auto, actions } = useAppState()
  useAutoForecast()
  const live = mode === "live"
  const notReady = useNotReady(["forecast", "cheques"])
  const [updating, setUpdating] = useState(false)
  // Calendar is the main working view; the tables stay available
  const [view, setView] = useState<"calendar" | "weekly" | "daily">("calendar")
  const [week, setWeek] = useState("all")
  const [onlyShort, setOnlyShort] = useState(false)
  const update = async () => {
    setUpdating(true)
    const err = await actions.refreshForecast()
    setUpdating(false)
    if (err) toast.error("The forecast was not updated", { description: err })
    else toast.success("Forecast updated from the latest bank, cheque and balance data")
  }
  const run = forecastInfo?.run
  const estimated = !live || run?.inflowEstimateStatus === "ESTIMATED"
  const horizon = live ? "84 days" : `${FORECAST_WEEKS} weeks`
  const receiptsCell = (v: number) => (estimated ? fmt.amt(v) : <span className="text-muted-foreground">Not estimated</span>)
  const updateButton = live && <Button variant="outline" onClick={() => void update()} disabled={updating}><RefreshCw />{updating ? "Updating…" : run ? "Update forecast" : "Create forecast"}</Button>

  if (live && (notReady || !run)) {
    return (
      <>
        <PageHeader title="Cash-Flow Forecast" description="Expected cash position for the next 84 days, from the Actual Bank Balance the bank confirmed." />
        {notReady ? <DataState resources={["forecast", "cheques"]} what="The forecast" /> : (
          <Card className="items-center gap-3 px-4 py-12 text-center text-sm">
            {auto.updating ? "Making today's forecast…" : "No forecast has been made yet."}
            <span className="text-muted-foreground max-w-[60ch]">{auto.error ?? "A forecast starts from the confirmed bank balance, so the company current account needs at least one imported bank statement."}</span>
            {!auto.updating && updateButton}
          </Card>
        )}
      </>
    )
  }
  const days = forecast.days.filter((d) => (week === "all" || String(d.week) === week) && (!onlyShort || d.closing < 0))
  const shortDays = forecast.days.filter((d) => d.closing < 0).length
  const weekLabel = (w: number) => {
    const wk = forecast.weeks[w - 1]
    return `Week ${w} · ${fmt.range(wk.week_start, wk.week_end)}`
  }

  return (
    <>
      <PageHeader
        title="Cash-Flow Forecast"
        description={run
          ? <>Expected cash position for 84 days, <b className="text-foreground font-medium">{fmt.day(run.horizonStartDate)} to {fmt.day(run.horizonEndDate)}</b> — starting the day after the Actual Bank Balance confirmed on {fmt.dateTimeFull(colomboLocal(run.openingBalanceAsOf))}. Select a date to see what moves the balance, or add a cheque payment.</>
          : `Expected cash position for the next ${FORECAST_WEEKS} weeks, Monday to Sunday. Select a date to see what moves the balance, or add a cheque payment.`}
        actions={<>{updateButton}<Segmented value={view} onChange={setView} options={[{ value: "calendar", label: "Calendar" }, { value: "weekly", label: "Weekly summary" }, { value: "daily", label: "Daily view" }]} /></>}
      />
      {run && <ForecastNotices warnings={run.warnings} outdated={!!forecastInfo?.outdated} autoError={auto.error} madeAt={run.createdAt} onUpdate={() => void update()} updating={updating} />}
      <StatRow items={[
        { label: "Actual Bank Balance (opening)", value: fmt.lkr0(run ? run.openingBalance : kpis.actualBalance), sub: <span className="inline-flex items-center gap-1"><Check className="size-3" />Bank data · {run ? fmt.dateTime(colomboLocal(run.openingBalanceAsOf)) : fmt.dateTime(kpis.lastSync)}</span> },
        { label: `Expected receipts · ${horizon}`, value: estimated ? fmt.lkr0(kpis.forecastReceipts) : "Not estimated", sub: "Forecast" },
        { label: `Expected cheque outflows · ${horizon}`, value: fmt.lkr0(kpis.forecastOutflow), sub: "Forecast" },
        { label: `${balanceLabel(kpis.forecastClosing ?? 0)}, ${fmt.short(forecast.weeks.at(-1)?.week_end)}`, value: fmt.lkr0(Math.abs(kpis.forecastClosing ?? 0)), negative: (kpis.forecastClosing ?? 0) < 0, sub: live ? "Forecast · end of horizon" : `Forecast · end of week ${FORECAST_WEEKS}` },
        { label: "Days short of cash", value: shortDays, negative: shortDays > 0, sub: forecast.shortages[0] ? `First ${fmt.day(forecast.shortages[0].first_date)}` : `None in ${horizon}` },
      ]} />

      {view === "calendar" ? (
        <CashFlowCalendar />
      ) : view === "weekly" ? (
        <Card>
          <CardHeader>
            <CardTitle>Weekly summary</CardTitle>
            <CardDescription>{live
              ? "Opening + expected receipts − expected cheque outflows = expected closing balance, carried into the next period. Each period is 7 days from the forecast start; receipts are by expected bank arrival date."
              : "Opening + expected receipts − expected cheque outflows = expected closing balance, carried into the next week. Predicted sales are shown by sales date; receipts by arrival date."}</CardDescription>
          </CardHeader>
          <DataTable<ForecastWeek>
            rows={forecast.weeks}
            rowKey={(r) => String(r.week)}
            maxHeight="none"
            rowClassName={(r) => (r.shortage_days > 0 ? shortageRow : "")}
            footer={(rs) => <><span>{horizon} · amounts in LKR</span><span className="tabular">Expected receipts {estimated ? fmt.lkr0(sum(rs, (r) => r.receipts)) : "not estimated"} · Expected cheque outflows {fmt.lkr0(sum(rs, (r) => r.cheque_out))}</span></>}
            columns={[
              { key: "week", header: live ? "Period" : "Week", cell: (r) => <CellStack className="whitespace-nowrap" main={<b>W{r.week}</b>} sub={fmt.range(r.week_start, r.week_end)} /> },
              { key: "open", header: "Opening", numeric: true, cell: (r) => <ForecastBalance value={r.opening} /> },
              { key: "cash", header: live ? "Expected cash deposits" : "Predicted cash sales", headClassName: wrapHead, numeric: true, cell: (r) => <span className="text-muted-foreground">{receiptsCell(r.cash_sales)}</span> },
              { key: "card", header: live ? "Expected card settlements" : "Predicted card sales", headClassName: wrapHead, numeric: true, cell: (r) => <span className="text-muted-foreground">{receiptsCell(r.card_sales)}</span> },
              { key: "rec", header: "Expected receipts", headClassName: wrapHead, numeric: true, cell: (r) => receiptsCell(r.receipts) },
              { key: "out", header: "Expected cheque outflows", headClassName: wrapHead, numeric: true, cell: (r) => (r.cheque_out ? "−" + fmt.amt(r.cheque_out) : "0.00") },
              { key: "close", header: "Expected closing balance", headClassName: wrapHead, numeric: true, cell: (r) => <ForecastBalance value={r.closing} className="font-semibold" /> },
              {
                // one figure per row: if the week ends at its lowest balance, that Cash Shortage is already in the closing column
                key: "low", header: "Lowest daily balance", headClassName: wrapHead, cell: (r) => {
                  if (!r.first_shortage_date) return <CellStack main={<span className="tabular">{fmt.amt(r.lowest_balance)}</span>} sub={fmt.day(r.lowest_date)} />
                  const days = `${r.shortage_days} ${r.shortage_days > 1 ? "days" : "day"} short from ${fmt.day(r.first_shortage_date)}`
                  return Math.abs(r.lowest_balance - r.closing) < 0.005
                    ? <CellStack main={<Badge variant="destructive"><AlertTriangle />Cash Shortage at week end</Badge>} sub={`${days} · highest in week`} />
                    : <CellStack main={<Badge variant="destructive"><AlertTriangle />Cash Shortage {fmt.lkr0(r.shortage_amount)}</Badge>} sub={`${days} · largest ${fmt.day(r.lowest_date)}`} />
                },
              },
              ...(live ? [] : [{ key: "range", header: "Likely closing range", headClassName: wrapHead, numeric: true, cell: range }]),
            ]}
          />
        </Card>
      ) : (
        <Card>
          <CardHeader>
            <CardTitle>Daily view</CardTitle>
            <CardDescription>Each day's expected movements, to find shortages inside a week. Shortage days are marked in red.</CardDescription>
          </CardHeader>
          <CardContent className="flex flex-wrap items-center gap-4">
            <Select value={week} onValueChange={setWeek}>
              <SelectTrigger aria-label="Week" className="w-64"><SelectValue /></SelectTrigger>
              <SelectContent>
                <SelectItem value="all">{live ? "All 84 days" : `All ${FORECAST_WEEKS} weeks`}</SelectItem>
                {forecast.weeks.map((w) => <SelectItem key={w.week} value={String(w.week)}>{weekLabel(w.week)}</SelectItem>)}
              </SelectContent>
            </Select>
            <div className="flex items-center gap-2">
              <Checkbox id="only-short" checked={onlyShort} onCheckedChange={(v) => setOnlyShort(v === true)} />
              <Label htmlFor="only-short" className="font-normal">Only shortage days</Label>
            </div>
          </CardContent>
          <DataTable<ForecastDay>
            rows={days}
            rowKey={(r) => r.date}
            maxHeight={640}
            groupBy={(r) => weekLabel(r.week)}
            rowClassName={(r) => (r.closing < 0 ? shortageRow : "")}
            empty="No shortage days in this range."
            columns={[
              { key: "date", header: "Date", cell: (r) => <b className="font-semibold whitespace-nowrap">{fmt.day(r.date)}</b> },
              { key: "open", header: "Opening", numeric: true, cell: (r) => <ForecastBalance value={r.opening} /> },
              { key: "cash", header: live ? "Expected cash deposits" : "Predicted cash sales", headClassName: wrapHead, numeric: true, cell: (r) => <span className="text-muted-foreground">{receiptsCell(r.cash_sales)}</span> },
              { key: "card", header: live ? "Expected card settlements" : "Predicted card sales", headClassName: wrapHead, numeric: true, cell: (r) => <span className="text-muted-foreground">{receiptsCell(r.card_sales)}</span> },
              { key: "rec", header: "Expected receipts", headClassName: wrapHead, numeric: true, cell: (r) => (!estimated ? receiptsCell(0) : r.receipts ? fmt.amt(r.receipts) : <span className="text-muted-foreground">—</span>) },
              { key: "out", header: "Expected cheque outflows", headClassName: wrapHead, numeric: true, cell: (r) => (r.cheque_out ? <CellStack main={"−" + fmt.amt(r.cheque_out)} sub={`${r.cheques} ${r.cheques > 1 ? "cheques" : "cheque"}`} /> : <span className="text-muted-foreground">—</span>) },
              { key: "close", header: "Expected closing balance", headClassName: wrapHead, numeric: true, cell: (r) => <ForecastBalance value={r.closing} className="font-semibold" /> },
              ...(live ? [] : [{ key: "range", header: "Likely range", numeric: true, cell: range }]),
              { key: "top", header: "Largest outflows", cell: (r) => <span className="text-muted-foreground block min-w-56 text-xs">{r.top_outflows}</span> },
            ]}
          />
        </Card>
      )}
      <ShortageAlerts days={forecast.days} />
      <Card>
        <CardHeader>
          <CardTitle>Expected daily closing balance</CardTitle>
          <CardDescription>{live ? "Company current account" : "Company current account · shaded area is the likely range, which widens further out"}</CardDescription>
        </CardHeader>
        <CardContent className="grid gap-2">
          <Legend items={live
            ? [{ label: "Expected closing balance", color: "var(--chart-4)", kind: "line" }, { label: "Below zero", color: "var(--destructive)", kind: "dot" }]
            : [{ label: "Expected closing balance", color: "var(--chart-4)", kind: "line" }, { label: "Likely range (80%)", color: "var(--chart-2)", kind: "band" }, { label: "Below zero", color: "var(--destructive)", kind: "dot" }]} />
          <BalanceChart days={forecast.days} showBand={!live} />
        </CardContent>
      </Card>
      <Alert variant="info">
        <Info />
        <AlertTitle>How receipts are timed</AlertTitle>
        {live ? (
          <AlertDescription>
            Expected receipts are cash sales deposits and card sales settlements only, each forecast separately. Each day receives what the same
            weekday 52 weeks earlier received, scaled by the growth rate: this year's collections over the same weekdays a year earlier, across
            the 8 weeks before the balance date. A day's collections come from the bank statement when one covers it, otherwise from recorded
            sales (historical imports and Daily Sales entries) received one day after the sale date — never both, so the same money is never
            counted twice. Days with neither are left out, not counted as zero. The forecast is made again automatically each day and whenever
            the data changes. Cheques are deducted on their expected clearing date.
          </AlertDescription>
        ) : (
        <AlertDescription>
          Only cash sales and card payments are predicted, from last year's comparable period and weekday pattern, revised as this year's actuals arrive.
          Monday–Thursday sales normally reach the current account the next morning; Friday, Saturday and Sunday sales on Monday (card sales via the card settlement account).
          Holidays or bank delays can shift this. Cash deposits and card settlement transfers are the same sales money arriving, not new income, and bank-confirmed amounts replace forecasts.
        </AlertDescription>
        )}
      </Alert>
    </>
  )
}

/**
 * The forecast service's own data-quality labels, plus a note when cheques changed after the forecast
 * was made or when today's automatic update failed.
 */
function ForecastNotices({ warnings, outdated, autoError, madeAt, onUpdate, updating }: {
  warnings: ForecastWarning[]; outdated: boolean; autoError: string | null; madeAt: string; onUpdate: () => void; updating: boolean
}) {
  const TITLE: Record<string, string> = {
    BALANCE_STALE: "The opening balance is out of date",
    INSUFFICIENT_HISTORY: "Receipts could not be estimated",
    INCOMPLETE_CLASSIFICATION: "Some bank transactions are not classified",
    PATTERN_FROM_SALES_HISTORY: "Last year's pattern partly comes from recorded sales",
    GROWTH_RATIO_OUT_OF_RANGE: "This year's collections differ strongly from last year",
    GROWTH_FROM_SALES_RECORDS: "This year's growth partly comes from daily sales",
  }
  return (
    <>
      {autoError && (
        <Alert variant="destructive">
          <AlertTriangle />
          <AlertTitle>The forecast could not be updated automatically</AlertTitle>
          <AlertDescription>
            <p>{autoError} Showing the forecast made {fmt.dateTimeFull(colomboLocal(madeAt))}.</p>
            <Button variant="outline" size="sm" className="mt-1" onClick={onUpdate} disabled={updating}><RefreshCw />Try again</Button>
          </AlertDescription>
        </Alert>
      )}
      {warnings.map((w) => (
        <Alert key={w.code} variant={w.code === "BALANCE_STALE" ? "destructive" : "info"}>
          {w.code === "BALANCE_STALE" ? <AlertTriangle /> : <Info />}
          <AlertTitle>{TITLE[w.code] ?? "Forecast data notice"}</AlertTitle>
          <AlertDescription>{w.message}</AlertDescription>
        </Alert>
      ))}
      {outdated && (
        <Alert variant="info">
          <Info />
          <AlertTitle>Cheque payments changed after this forecast was made</AlertTitle>
          <AlertDescription>
            <p>This forecast was made {fmt.dateTimeFull(colomboLocal(madeAt))} and does not include the change yet.</p>
            <Button variant="outline" size="sm" className="mt-1" onClick={onUpdate} disabled={updating}><RefreshCw />Update forecast</Button>
          </AlertDescription>
        </Alert>
      )}
    </>
  )
}
