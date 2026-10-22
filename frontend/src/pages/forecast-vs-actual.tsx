import { useEffect, useState } from "react"
import { AlertTriangle, Check, Info, RefreshCw, TrendingUp } from "lucide-react"
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Card, CardAction, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card"
import { Input } from "@/components/ui/input"
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select"
import { useAppState } from "@/components/app/app-state"
import { CellStack, DataTable } from "@/components/app/data-table"
import { DataState, LoadingCard, useNotReady } from "@/components/app/data-state"
import { StatRow } from "@/components/app/kpi-card"
import { PageHeader } from "@/components/app/page-header"
import { Segmented } from "@/components/app/segmented"
import { GroupedBarChart } from "@/components/charts/bar-charts"
import { Legend } from "@/components/charts/chart-tooltip"
import { forecastVsActual as demoRows } from "@/data"
import type { ForecastVsActual } from "@/data/types"
import { colomboLocal, forecastVsActualWeeks } from "@/lib/api/adapters"
import { errorMessage } from "@/lib/api/client"
import { flowledger, type ForecastWarning } from "@/lib/api/flowledger"
import { endOfLastMonth, monthLabel } from "@/lib/bank-template"
import { fmt, sum } from "@/lib/format"
import { cn } from "@/lib/utils"

type Preset = "4" | "8" | "all" | "custom"
type Metric = "in" | "out" | "closing"
const SERIES = [
  { key: "forecast", label: "Forecast", color: "var(--chart-2)" },
  { key: "actual", label: "Actual (bank-confirmed)", color: "var(--chart-5)" },
] as const
const METRIC: Record<Metric, { label: string; f: (r: ForecastVsActual) => number | null; a: (r: ForecastVsActual) => number | null }> = {
  in: { label: "Receipts", f: (r) => r.forecast_in, a: (r) => r.actual_in },
  out: { label: "Cheque outflows", f: (r) => r.forecast_out, a: (r) => r.actual_out },
  closing: { label: "Forecast closing vs. Actual Bank Balance", f: (r) => r.forecast_closing, a: (r) => r.actual_closing },
}
const F = <Badge variant="dashed" className="ml-1"><TrendingUp />F</Badge>
const A = <Badge variant="soft" className="ml-1"><Check />A</Badge>
/** Sum that stays unknown if any part is unknown. */
const total = (rows: ForecastVsActual[], f: (r: ForecastVsActual) => number | null) =>
  rows.some((r) => f(r) == null) ? null : sum(rows, (r) => f(r) ?? 0)
const pct = (a: number | null, f: number | null) => (a == null || f == null || !f ? "—" : fmt.pct((a - f) / f))

interface Comparison { rows: ForecastVsActual[]; warnings: ForecastWarning[]; comparedThrough: string | null }

/**
 * Live: compares one saved forecast run with the bank-confirmed actuals, by actual transaction date. Which run is the
 * baseline for a period is not decided yet, so the user picks the run (newest first).
 */
function useComparison(runId: string | null) {
  const { company, mode } = useAppState()
  const [state, setState] = useState<{ loading: boolean; error: string | null; data: Comparison | null }>({ loading: false, error: null, data: null })
  const [attempt, setAttempt] = useState(0)
  useEffect(() => {
    if (mode !== "live" || !company || !runId) return
    let current = true
    setState({ loading: true, error: null, data: null })
    flowledger.forecastVsActual(company.id, runId)
      .then((fva) => current && setState({ loading: false, error: null, data: { rows: forecastVsActualWeeks(fva), warnings: fva.warnings, comparedThrough: fva.comparedThrough } }))
      .catch((e) => current && setState({ loading: false, error: errorMessage(e), data: null }))
    return () => { current = false }
  }, [company, mode, runId, attempt])
  return { ...state, retry: () => setAttempt((n) => n + 1) }
}

export default function ForecastVsActualPage() {
  const { mode, forecastInfo, today } = useAppState()
  const live = mode === "live"
  const runsNotReady = useNotReady(["forecast"])
  const runs = forecastInfo?.runs ?? []
  const [runId, setRunId] = useState<string | null>(null)
  // Bank transactions are imported after each month ends, so only days up to the end of last month have actuals.
  const actualsThrough = endOfLastMonth(today)
  const comparable = (r: { horizonStartDate: string }) => r.horizonStartDate <= actualsThrough
  const selected = runId ?? runs.find(comparable)?.id ?? runs[0]?.id ?? null
  const comparison = useComparison(live ? selected : null)
  const all = live ? comparison.data?.rows ?? [] : demoRows

  const [preset, setPreset] = useState<Preset>("all")
  const [metric, setMetric] = useState<Metric>("closing")
  const [from, setFrom] = useState("")
  const [to, setTo] = useState("")
  const applyPreset = (p: Preset) => {
    setPreset(p)
    if (p === "all" || !all.length) { setFrom(""); setTo(""); return }
    if (p === "custom") return
    setFrom(all[Math.max(0, all.length - Number(p))].week_start)
    setTo(all[all.length - 1].week_start)
  }
  const rows = all.filter((r) => (!from || r.week_start >= from) && (!to || r.week_start <= to))
  const fi = total(rows, (r) => r.forecast_in), ai = sum(rows, (r) => r.actual_in), fo = sum(rows, (r) => r.forecast_out), ao = sum(rows, (r) => r.actual_out)
  const m = METRIC[metric]
  const chartData = rows.map((r) => ({ week: r.week_start, forecast: m.f(r), actual: m.a(r) }))
  const series = metric === "closing" ? [SERIES[0], { ...SERIES[1], label: "Actual Bank Balance" }] : [...SERIES]
  const diff = (v: number | null) => <span className={cn(v != null && v < 0 && "text-destructive")}>{fmt.signed(v)}</span>
  const period = "Weeks starting"

  const header = <PageHeader title="Forecast vs Actual" description="How close earlier forecasts were to what the bank actually confirmed. Forecast figures are marked F, bank-confirmed actuals A." />
  if (live && (runsNotReady || !runs.length)) {
    return (
      <>
        {header}
        {runsNotReady ? <DataState resources={["forecast"]} what="Saved forecasts" /> : (
          <Card className="items-center gap-3 px-4 py-12 text-center text-sm">
            No forecast has been saved yet.
            <span className="text-muted-foreground">Make a forecast first; it can be compared with the bank once statements for its days are imported.</span>
          </Card>
        )}
      </>
    )
  }

  return (
    <>
      {header}
      {live && (
        <Alert variant="info">
          <Info />
          <AlertTitle>Compared up to {fmt.date(actualsThrough)}</AlertTitle>
          <AlertDescription>
            Bank transactions are imported once a month, after the month ends, so {monthLabel(today.slice(0, 7))} is compared once its
            transactions are imported. Forecasts made this month have nothing to compare yet.
          </AlertDescription>
        </Alert>
      )}
      {live && (
        <div className="flex flex-wrap items-center gap-2">
          <span className="text-muted-foreground text-xs">Forecast compared</span>
          <Select value={selected ?? undefined} onValueChange={(v) => { setRunId(v); setPreset("all"); setFrom(""); setTo("") }}>
            <SelectTrigger aria-label="Forecast compared" className="w-96"><SelectValue /></SelectTrigger>
            <SelectContent>
              {runs.map((r) => (
                <SelectItem key={r.id} value={r.id}>
                  {fmt.range(r.horizonStartDate, r.horizonEndDate)} · made {fmt.dateTime(colomboLocal(r.createdAt))}{!comparable(r) && " · nothing to compare yet"}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
        </div>
      )}
      {live && comparison.error && (
        <Alert variant="destructive">
          <AlertTriangle />
          <AlertTitle>The comparison could not be loaded</AlertTitle>
          <AlertDescription>
            <p>{comparison.error}</p>
            <Button variant="outline" size="sm" className="mt-1" onClick={comparison.retry}><RefreshCw />Try again</Button>
          </AlertDescription>
        </Alert>
      )}
      {live && comparison.loading && <LoadingCard>Loading the comparison…</LoadingCard>}
      {live && comparison.data?.warnings.map((w) => (
        <Alert key={w.code} variant="info"><Info /><AlertDescription>{w.message}</AlertDescription></Alert>
      ))}
      {(!live || comparison.data) && (
      <>
      <div className="flex flex-wrap items-center gap-2">
        <Segmented value={preset === "custom" ? ("" as Preset) : preset} onChange={applyPreset} options={[{ value: "4", label: "Last 4 weeks" }, { value: "8", label: "Last 8 weeks" }, { value: "all", label: "All" }]} />
        <span className="text-muted-foreground ml-2 text-xs">{period}</span>
        <Input type="date" className="w-40" value={from} onChange={(e) => { setFrom(e.target.value); setPreset("custom") }} aria-label="From" />
        <span className="text-muted-foreground">–</span>
        <Input type="date" className="w-40" value={to} onChange={(e) => { setTo(e.target.value); setPreset("custom") }} aria-label="To" />
      </div>
      <StatRow items={[
        { label: "Expected receipts", value: fmt.compact(fi) }, { label: "Confirmed receipts", value: fmt.compact(ai) }, { label: "Receipts difference", value: pct(ai, fi) },
        { label: "Expected cheque outflows", value: fmt.compact(fo) }, { label: "Confirmed payments", value: fmt.compact(ao) }, { label: "Payments difference", value: pct(ao, fo) },
        { label: "Weeks", value: rows.length },
      ]} />
      <Card>
        <CardHeader>
          <CardTitle>{m.label} by week</CardTitle>
          <CardDescription>{live
            ? `Weeks from Monday to Sunday; only days with imported bank transactions, up to the end of last month${comparison.data?.comparedThrough ? `, up to ${fmt.date(comparison.data.comparedThrough)}` : ""}`
            : "Forecast made at the start of each week vs. bank-confirmed actual"}</CardDescription>
          <CardAction>
            <Segmented value={metric} onChange={setMetric} options={[{ value: "closing", label: "Closing balance" }, { value: "in", label: "Receipts" }, { value: "out", label: "Cheque outflows" }]} />
          </CardAction>
        </CardHeader>
        <CardContent className="grid gap-2">
          <Legend items={series.map((s) => ({ label: s.label, color: s.color }))} />
          {rows.length
            ? <GroupedBarChart data={chartData} xKey="week" xFormat={fmt.axisDate} series={series} title={(r) => "Week of " + fmt.day(r.week)} height={240} />
            : <p className="text-muted-foreground py-10 text-center text-sm">No bank-confirmed days to compare yet.</p>}
        </CardContent>
      </Card>
      <Card>
        <CardHeader><CardTitle>Weekly comparison</CardTitle><CardDescription>Difference = actual − forecast. Amounts in LKR.</CardDescription></CardHeader>
        <DataTable<ForecastVsActual>
          rows={rows}
          rowKey={(r) => r.week_start}
          defaultSort={{ key: "week", dir: "desc" }}
          maxHeight="none"
          empty={live ? "No days of this forecast have imported bank transactions yet." : "No weeks in this date range."}
          columns={[
            { key: "week", header: "Week (Mon–Sun)", sortValue: (r) => r.week_start, cell: (r) => <CellStack className="whitespace-nowrap" main={<b className="font-semibold">{fmt.range(r.week_start, r.week_end)}</b>} sub={r.days_compared != null && r.days_compared < 7 ? `${r.days_compared} of 7 days confirmed` : undefined} /> },
            { key: "fi", header: <>Expected receipts{F}</>, numeric: true, sortValue: (r) => r.forecast_in ?? 0, cell: (r) => <span className="text-muted-foreground">{r.forecast_in == null ? "Not estimated" : fmt.amt(r.forecast_in)}</span> },
            { key: "ai", header: <>Confirmed receipts{A}</>, numeric: true, sortValue: (r) => r.actual_in, cell: (r) => <CellStack main={<b className="font-semibold">{fmt.amt(r.actual_in)}</b>} sub={diff(r.diff_in)} /> },
            { key: "fo", header: <>Expected cheque outflows{F}</>, numeric: true, sortValue: (r) => r.forecast_out, cell: (r) => <span className="text-muted-foreground">{fmt.amt(r.forecast_out)}</span> },
            { key: "ao", header: <>Confirmed payments{A}</>, numeric: true, sortValue: (r) => r.actual_out, cell: (r) => <CellStack main={<b className="font-semibold">{fmt.amt(r.actual_out)}</b>} sub={fmt.signed(r.diff_out)} /> },
            { key: "fc", header: <>Expected closing balance{F}</>, numeric: true, sortValue: (r) => r.forecast_closing, cell: (r) => <span className="text-muted-foreground">{fmt.amt(r.forecast_closing)}</span> },
            { key: "ac", header: <>Actual Bank Balance{A}</>, numeric: true, sortValue: (r) => r.actual_closing ?? 0, cell: (r) => <b className="font-semibold">{fmt.amt(r.actual_closing)}</b> },
            { key: "dc", header: "Difference", numeric: true, sortValue: (r) => r.diff_closing ?? 0, cell: (r) => <b className="font-semibold">{diff(r.diff_closing)}</b> },
          ]}
        />
      </Card>
      </>
      )}
    </>
  )
}
