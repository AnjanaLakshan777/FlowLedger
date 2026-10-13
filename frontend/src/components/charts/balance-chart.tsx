import { Area, CartesianGrid, ComposedChart, Line, ReferenceLine, ResponsiveContainer, Tooltip, XAxis, YAxis } from "recharts"
import type { ForecastDay } from "@/data/types"
import { balanceAmount } from "@/components/app/forecast-balance"
import { fmt } from "@/lib/format"
import { ChartTooltipBox, type TipRow } from "./chart-tooltip"

type Day = ForecastDay & { baseline?: number }

/** Expected daily closing balance with a likely-range band; days below zero get red dots. */
export function BalanceChart({ days, height = 280, showBand = true, showBaseline = false }: { days: Day[]; height?: number; showBand?: boolean; showBaseline?: boolean }) {
  const data = days.map((d) => ({ ...d, range: [d.low, d.high] as [number, number] }))
  return (
    <div style={{ height }} role="img" aria-label="Projected daily closing balance">
      <ResponsiveContainer width="100%" height="100%">
        <ComposedChart data={data} margin={{ top: 8, right: 8, bottom: 0, left: 0 }}>
          <CartesianGrid vertical={false} stroke="var(--border)" />
          <XAxis dataKey="date" tickFormatter={fmt.axisDate} tickLine={false} axisLine={false} minTickGap={36} tick={{ fill: "var(--muted-foreground)", fontSize: 11.5 }} />
          <YAxis tickFormatter={fmt.axis} tickLine={false} axisLine={false} width={52} tick={{ fill: "var(--muted-foreground)", fontSize: 11.5 }} />
          {showBand && <Area dataKey="range" stroke="none" fill="var(--chart-1)" fillOpacity={0.28} isAnimationActive={false} activeDot={false} />}
          <ReferenceLine y={0} stroke="var(--destructive)" strokeDasharray="4 3" label={{ value: "LKR 0", position: "insideTopRight", fill: "var(--destructive)", fontSize: 11 }} />
          {showBaseline && <Line dataKey="baseline" stroke="var(--muted-foreground)" strokeWidth={2} strokeDasharray="5 4" dot={false} isAnimationActive={false} />}
          <Line
            dataKey="closing"
            stroke="var(--chart-4)"
            strokeWidth={2}
            isAnimationActive={false}
            dot={(p: { cx?: number; cy?: number; payload?: Day; index?: number }) =>
              p.payload && p.payload.closing < 0 ? <circle key={p.index} cx={p.cx} cy={p.cy} r={3.5} fill="var(--destructive)" /> : <g key={p.index} />
            }
            activeDot={{ r: 4, fill: "var(--background)", stroke: "var(--chart-4)", strokeWidth: 2 }}
          />
          <Tooltip
            cursor={{ stroke: "var(--ring)" }}
            content={({ active, payload }) => {
              if (!active || !payload?.length) return null
              const d = payload[0].payload as Day
              const rows: TipRow[] = [{ label: d.closing < 0 ? "Cash Shortage" : showBaseline ? "Scenario balance" : "Expected closing balance", value: balanceAmount(d.closing), negative: d.closing < 0 }]
              if (showBaseline && d.baseline !== undefined) rows.push({ label: "Baseline closing", value: fmt.lkr0(d.baseline) })
              if (showBand) rows.push({ label: "Likely range", value: `${fmt.compact(d.low)} – ${fmt.compact(d.high)}` })
              rows.push({ label: "Expected receipts", value: fmt.lkr0(d.receipts) }, { label: "Expected cheque outflows", value: fmt.lkr0(d.cheque_out) })
              return <ChartTooltipBox title={`${fmt.day(d.date)} · Week ${d.week}`} rows={rows} />
            }}
          />
        </ComposedChart>
      </ResponsiveContainer>
    </div>
  )
}
