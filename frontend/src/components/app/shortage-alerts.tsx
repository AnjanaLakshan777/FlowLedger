import { useState, type ReactNode } from "react"
import { AlertTriangle } from "lucide-react"
import { Button } from "@/components/ui/button"
import { Card, CardAction, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card"
import type { ForecastDay } from "@/data/types"
import { fmt } from "@/lib/format"

const SHOW_PAYMENTS = 4

/**
 * Cash-shortage warnings, one row per forecast date below zero — the one place (with errors) where red is used.
 * `initial` rows are shown first; the rest open with "Show all".
 */
export function ShortageAlerts({ days, action, initial = 5 }: { days: ForecastDay[]; action?: ReactNode; initial?: number }) {
  const [all, setAll] = useState(false)
  const short = days.filter((d) => d.closing < 0)
  if (!short.length) return null
  // first day of the run each short date belongs to, for "Still short since …"
  const runStart = new Map<string, string>()
  days.forEach((d, i) => { if (d.closing < 0) runStart.set(d.date, i > 0 && days[i - 1].closing < 0 ? runStart.get(days[i - 1].date)! : d.date) })
  const shown = all ? short : short.slice(0, initial)

  return (
    <Card className="border-destructive/45 gap-2 pb-0">
      <CardHeader>
        <CardTitle className="text-destructive">
          <AlertTriangle className="size-4" />
          Upcoming Cash Shortage warnings
        </CardTitle>
        <CardDescription>Each forecast date when the current account is expected to run short of cash</CardDescription>
        {action && <CardAction>{action}</CardAction>}
      </CardHeader>
      <CardContent className="px-0">
        {shown.map((d) => {
          const pays = d.payments.slice(0, SHOW_PAYMENTS)
          const more = d.payments.slice(SHOW_PAYMENTS)
          const since = runStart.get(d.date)!
          return (
            <div key={d.date} className="border-destructive/20 grid grid-cols-[minmax(0,1fr)] gap-x-6 gap-y-3 border-t px-4 py-3 md:grid-cols-[minmax(190px,auto)_minmax(0,1fr)]">
              <div>
                <div className="text-muted-foreground text-xs">Cash Shortage</div>
                <div className="text-destructive text-lg leading-tight font-semibold whitespace-nowrap tabular">{fmt.lkr0(-d.closing)}</div>
                <div className="mt-1 text-[13px] font-medium">{fmt.day(d.date)}</div>
                <div className="text-muted-foreground text-xs">{since === d.date ? "First day short" : `Still short since ${fmt.day(since)}`}</div>
              </div>
              <div className="min-w-0 text-[13px]">
                {pays.length ? (
                  <ul className="grid grid-cols-[minmax(0,1fr)] gap-0.5">
                    {pays.map((p) => (
                      <li key={p.cheque_no} className="flex items-baseline justify-between gap-3">
                        <span className="min-w-0 truncate">
                          {p.payee} <span className="text-muted-foreground">· {p.category} · #{p.cheque_no}</span>
                        </span>
                        <span className="tabular whitespace-nowrap">{fmt.amt(p.amount)}</span>
                      </li>
                    ))}
                    {more.length > 0 && (
                      <li className="text-muted-foreground flex justify-between gap-3">
                        <span>{more.length} more</span>
                        <span className="tabular whitespace-nowrap">{fmt.amt(more.reduce((t, p) => t + p.amount, 0))}</span>
                      </li>
                    )}
                  </ul>
                ) : <p className="text-muted-foreground">No cheque payments on this day.</p>}
                <p className="text-muted-foreground mt-1.5 text-xs tabular">
                  {d.opening < 0 ? `Started the day ${fmt.lkr0(-d.opening)} short` : `Started the day with ${fmt.lkr0(d.opening)}`};
                  {" "}expected receipts {fmt.lkr0(d.receipts)} came in and expected cheque outflows {fmt.lkr0(d.cheque_out)} went out, leaving a Cash Shortage of {fmt.lkr0(-d.closing)}.
                </p>
              </div>
            </div>
          )
        })}
        {short.length > initial && (
          <div className="border-destructive/20 border-t px-4 py-2">
            <Button variant="ghost" size="sm" onClick={() => setAll((v) => !v)} aria-expanded={all}>
              {all ? "Show fewer" : "Show all dates"}
            </Button>
          </div>
        )}
      </CardContent>
    </Card>
  )
}
