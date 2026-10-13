import { useState } from "react"
import { Check, TrendingUp } from "lucide-react"
import { Badge } from "@/components/ui/badge"
import { Card, CardAction, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card"
import { Input } from "@/components/ui/input"
import { CellStack, DataTable } from "@/components/app/data-table"
import { StatRow } from "@/components/app/kpi-card"
import { PageHeader } from "@/components/app/page-header"
import { Segmented } from "@/components/app/segmented"
import { GroupedBarChart } from "@/components/charts/bar-charts"
import { Legend } from "@/components/charts/chart-tooltip"
import { forecastVsActual } from "@/data"
import type { ForecastVsActual } from "@/data/types"
import { fmt, sum } from "@/lib/format"
import { cn } from "@/lib/utils"

type Preset = "4" | "8" | "all" | "custom"
type Metric = "in" | "out" | "closing"
const SERIES = [
  { key: "forecast", label: "Forecast", color: "var(--chart-2)" },
  { key: "actual", label: "Actual (bank-confirmed)", color: "var(--chart-5)" },
] as const
const METRIC: Record<Metric, { label: string; f: (r: ForecastVsActual) => number; a: (r: ForecastVsActual) => number }> = {
  in: { label: "Receipts", f: (r) => r.forecast_in, a: (r) => r.actual_in },
  out: { label: "Cheque outflows", f: (r) => r.forecast_out, a: (r) => r.actual_out },
  closing: { label: "Forecast closing vs. Actual Bank Balance", f: (r) => r.forecast_closing, a: (r) => r.actual_closing },
}
const F = <Badge variant="dashed" className="ml-1"><TrendingUp />F</Badge>
const A = <Badge variant="soft" className="ml-1"><Check />A</Badge>

export default function ForecastVsActualPage() {
  const all = forecastVsActual
  const [preset, setPreset] = useState<Preset>("all")
  const [metric, setMetric] = useState<Metric>("closing")
  const [from, setFrom] = useState("")
  const [to, setTo] = useState("")
  const applyPreset = (p: Preset) => {
    setPreset(p)
    if (p === "all") { setFrom(""); setTo(""); return }
    if (p === "custom") return
    setFrom(all[Math.max(0, all.length - Number(p))].week_start)
    setTo(all[all.length - 1].week_start)
  }
  const rows = all.filter((r) => (!from || r.week_start >= from) && (!to || r.week_start <= to))
  const fi = sum(rows, (r) => r.forecast_in), ai = sum(rows, (r) => r.actual_in), fo = sum(rows, (r) => r.forecast_out), ao = sum(rows, (r) => r.actual_out)
  const m = METRIC[metric]
  const chartData = rows.map((r) => ({ week: r.week_start, forecast: m.f(r), actual: m.a(r) }))
  const series = metric === "closing" ? [SERIES[0], { ...SERIES[1], label: "Actual Bank Balance" }] : [...SERIES]
  const diff = (v: number) => <span className={cn(v < 0 && "text-destructive")}>{fmt.signed(v)}</span>

  return (
    <>
      <PageHeader title="Forecast vs Actual" description="How close earlier forecasts were to what the bank actually confirmed. Forecast figures are marked F, bank-confirmed actuals A." />
      <div className="flex flex-wrap items-center gap-2">
        <Segmented value={preset === "custom" ? ("" as Preset) : preset} onChange={applyPreset} options={[{ value: "4", label: "Last 4 weeks" }, { value: "8", label: "Last 8 weeks" }, { value: "all", label: "All" }]} />
        <span className="text-muted-foreground ml-2 text-xs">Weeks starting</span>
        <Input type="date" className="w-40" value={from} onChange={(e) => { setFrom(e.target.value); setPreset("custom") }} aria-label="From" />
        <span className="text-muted-foreground">–</span>
        <Input type="date" className="w-40" value={to} onChange={(e) => { setTo(e.target.value); setPreset("custom") }} aria-label="To" />
      </div>
      <StatRow items={[
        { label: "Expected receipts", value: fmt.compact(fi) }, { label: "Confirmed receipts", value: fmt.compact(ai) }, { label: "Receipts difference", value: fmt.pct(fi ? (ai - fi) / fi : 0) },
        { label: "Expected cheque outflows", value: fmt.compact(fo) }, { label: "Confirmed payments", value: fmt.compact(ao) }, { label: "Payments difference", value: fmt.pct(fo ? (ao - fo) / fo : 0) },
        { label: "Weeks", value: rows.length },
      ]} />
      <Card>
        <CardHeader>
          <CardTitle>{m.label} by week</CardTitle>
          <CardDescription>Forecast made at the start of each week vs. bank-confirmed actual</CardDescription>
          <CardAction>
            <Segmented value={metric} onChange={setMetric} options={[{ value: "closing", label: "Closing balance" }, { value: "in", label: "Receipts" }, { value: "out", label: "Cheque outflows" }]} />
          </CardAction>
        </CardHeader>
        <CardContent className="grid gap-2">
          <Legend items={series.map((s) => ({ label: s.label, color: s.color }))} />
          <GroupedBarChart data={chartData} xKey="week" xFormat={fmt.axisDate} series={series} title={(r) => "Week of " + fmt.day(r.week)} height={240} />
        </CardContent>
      </Card>
      <Card>
        <CardHeader><CardTitle>Weekly comparison</CardTitle><CardDescription>Difference = actual − forecast. Amounts in LKR.</CardDescription></CardHeader>
        <DataTable<ForecastVsActual>
          rows={rows}
          rowKey={(r) => r.week_start}
          defaultSort={{ key: "week", dir: "desc" }}
          maxHeight="none"
          empty="No weeks in this date range."
          columns={[
            { key: "week", header: "Period", sortValue: (r) => r.week_start, cell: (r) => <b className="font-semibold whitespace-nowrap">{fmt.range(r.week_start, r.week_end)}</b> },
            { key: "fi", header: <>Expected receipts{F}</>, numeric: true, sortValue: (r) => r.forecast_in, cell: (r) => <span className="text-muted-foreground">{fmt.amt(r.forecast_in)}</span> },
            { key: "ai", header: <>Confirmed receipts{A}</>, numeric: true, sortValue: (r) => r.actual_in, cell: (r) => <CellStack main={<b className="font-semibold">{fmt.amt(r.actual_in)}</b>} sub={diff(r.diff_in)} /> },
            { key: "fo", header: <>Expected cheque outflows{F}</>, numeric: true, sortValue: (r) => r.forecast_out, cell: (r) => <span className="text-muted-foreground">{fmt.amt(r.forecast_out)}</span> },
            { key: "ao", header: <>Confirmed payments{A}</>, numeric: true, sortValue: (r) => r.actual_out, cell: (r) => <CellStack main={<b className="font-semibold">{fmt.amt(r.actual_out)}</b>} sub={fmt.signed(r.diff_out)} /> },
            { key: "fc", header: <>Expected closing balance{F}</>, numeric: true, sortValue: (r) => r.forecast_closing, cell: (r) => <span className="text-muted-foreground">{fmt.amt(r.forecast_closing)}</span> },
            { key: "ac", header: <>Actual Bank Balance{A}</>, numeric: true, sortValue: (r) => r.actual_closing, cell: (r) => <b className="font-semibold">{fmt.amt(r.actual_closing)}</b> },
            { key: "dc", header: "Difference", numeric: true, sortValue: (r) => r.diff_closing, cell: (r) => <b className="font-semibold">{diff(r.diff_closing)}</b> },
          ]}
        />
      </Card>
    </>
  )
}
