import { Bar, BarChart, CartesianGrid, Cell, ComposedChart, ReferenceArea, ReferenceLine, ResponsiveContainer, Scatter, Tooltip, XAxis, YAxis } from "recharts"
import type { ForecastWeek } from "@/data/types"
import { balanceAmount } from "@/components/app/forecast-balance"
import { fmt } from "@/lib/format"
import { ChartTooltipBox } from "./chart-tooltip"

const axisTick = { fill: "var(--muted-foreground)", fontSize: 11.5 }
/** One decimal ("3.1M", "38K") so labels on neighbouring weeks don't collide on narrow screens. */
const short1 = (v: number) => (v >= 1e6 ? (v / 1e6).toFixed(1) + "M" : v >= 1e3 ? Math.round(v / 1e3) + "K" : String(Math.round(v)))

/** Weekly closing balance bars, a marker for the lowest daily balance, and the shortage amount above weeks that run short. */
export function WeeklyBalanceChart({ weeks, height = 230 }: { weeks: ForecastWeek[]; height?: number }) {
  const data = weeks.map((w) => ({ ...w, label: "W" + w.week }))
  const hasNeg = data.some((d) => d.lowest_balance < 0)
  const short = data.filter((d) => d.shortage_days > 0)
  // stagger labels of side-by-side shortage weeks so they never overlap, even on narrow screens
  const lowered = new Set<number>()
  short.forEach((d) => { if (short.some((p) => p.week === d.week - 1) && !lowered.has(d.week - 1)) lowered.add(d.week) })
  const summary = short.length
    ? `${short.length} of ${data.length} weeks run short of cash: ${short.map((d) => `${d.label} ${fmt.lkr0(d.shortage_amount)}`).join(", ")}`
    : "No week runs short of cash"
  return (
    <div style={{ height }} role="img" aria-label={`Weekly expected closing balance. ${summary}.`}>
      <ResponsiveContainer width="100%" height="100%">
        <ComposedChart data={data} margin={{ top: 8, right: 4, bottom: 0, left: 0 }}>
          <CartesianGrid vertical={false} stroke="var(--border)" />
          <XAxis dataKey="label" tickLine={false} axisLine={false} tick={axisTick} interval="preserveStartEnd" />
          <YAxis tickFormatter={fmt.axis} tickLine={false} axisLine={false} width={52} tick={axisTick} />
          {/* shortage amount at the top of each short week (positive — never a negative balance); no shading */}
          {short.map((d) => (
            <ReferenceArea key={d.week} x1={d.label} x2={d.label} fillOpacity={0} stroke="none" ifOverflow="extendDomain"
              label={{ value: short1(d.shortage_amount), position: "insideTop", offset: lowered.has(d.week) ? 19 : 5, fill: "var(--destructive)", fontSize: 10, fontWeight: 600 }} />
          ))}
          {hasNeg && <ReferenceLine y={0} stroke="var(--destructive)" strokeDasharray="4 3" />}
          <Bar dataKey="closing" radius={[3, 3, 0, 0]} maxBarSize={32} isAnimationActive={false}>
            {data.map((d) => <Cell key={d.week} fill={d.closing < 0 ? "var(--destructive)" : "var(--chart-3)"} />)}
          </Bar>
          <Scatter
            dataKey="lowest_balance"
            isAnimationActive={false}
            shape={(p: { cx?: number; cy?: number; payload?: ForecastWeek }) => (
              <line x1={(p.cx ?? 0) - 12} x2={(p.cx ?? 0) + 12} y1={p.cy} y2={p.cy} strokeWidth={2} strokeLinecap="round"
                stroke={p.payload && p.payload.lowest_balance < 0 ? "var(--destructive)" : "var(--foreground)"} />
            )}
          />
          <Tooltip
            cursor={{ fill: "var(--muted)", opacity: 0.6 }}
            content={({ active, payload }) => {
              if (!active || !payload?.length) return null
              const w = payload[0].payload as ForecastWeek
              return (
                <ChartTooltipBox
                  title={`Week ${w.week} · ${fmt.range(w.week_start, w.week_end)}`}
                  rows={w.shortage_days > 0 ? [
                    // short week: plain rows (no colour keys)
                    // one row when the week ends at its highest Cash Shortage (e.g. W11), so the same amount isn't shown twice
                    ...(w.closing < 0 && Math.abs(w.closing - w.lowest_balance) < 0.005
                      ? [{ label: "Cash Shortage at week end (highest in week)", value: balanceAmount(w.closing), negative: true }]
                      : [
                          { label: w.closing < 0 ? "Cash Shortage at week end" : "Expected closing balance", value: balanceAmount(w.closing), negative: w.closing < 0 },
                          { label: `Highest daily Cash Shortage, ${fmt.day(w.lowest_date)}`, value: fmt.lkr0(w.shortage_amount), negative: true },
                        ]),
                    { label: "Days short of cash", value: `${w.shortage_days} · from ${fmt.day(w.first_shortage_date)}`, negative: true },
                    { label: "Expected receipts", value: fmt.lkr0(w.receipts) },
                    { label: "Expected cheque outflows", value: fmt.lkr0(w.cheque_out) },
                  ] : [
                    { color: "var(--chart-3)", label: "Expected closing balance", value: fmt.lkr0(w.closing) },
                    { color: "var(--foreground)", label: "Lowest daily balance", value: fmt.lkr0(w.lowest_balance) },
                    { label: "Expected receipts", value: fmt.lkr0(w.receipts) },
                    { label: "Expected cheque outflows", value: fmt.lkr0(w.cheque_out) },
                  ]}
                />
              )
            }}
          />
        </ComposedChart>
      </ResponsiveContainer>
    </div>
  )
}

/** Two series side by side per category (e.g. forecast vs actual). */
export function GroupedBarChart<T extends Record<string, unknown>>({ data, xKey, xFormat, series, height = 220, title }: {
  data: T[]; xKey: keyof T & string; xFormat?: (v: string) => string; series: { key: keyof T & string; label: string; color: string }[]; height?: number; title?: (row: T) => string
}) {
  return (
    <div style={{ height }} role="img" aria-label={series.map((s) => s.label).join(" vs ")}>
      <ResponsiveContainer width="100%" height="100%">
        <BarChart data={data} margin={{ top: 8, right: 4, bottom: 0, left: 0 }} barGap={2}>
          <CartesianGrid vertical={false} stroke="var(--border)" />
          <XAxis dataKey={xKey} tickFormatter={xFormat} tickLine={false} axisLine={false} tick={axisTick} />
          <YAxis tickFormatter={fmt.axis} tickLine={false} axisLine={false} width={52} tick={axisTick} />
          {series.map((s) => <Bar key={s.key} dataKey={s.key} fill={s.color} radius={[3, 3, 0, 0]} maxBarSize={28} isAnimationActive={false} />)}
          <Tooltip
            cursor={{ fill: "var(--muted)", opacity: 0.6 }}
            content={({ active, payload }) => {
              if (!active || !payload?.length) return null
              const row = payload[0].payload as T
              return <ChartTooltipBox title={title ? title(row) : String(row[xKey])} rows={series.map((s) => ({ color: s.color, label: s.label, value: fmt.lkr0(Number(row[s.key])) }))} />
            }}
          />
        </BarChart>
      </ResponsiveContainer>
    </div>
  )
}
