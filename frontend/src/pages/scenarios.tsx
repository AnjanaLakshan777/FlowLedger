// DEFERRED: Scenario Analysis is optional and not routed in the MVP. Kept compiling for a later release.
import { useMemo, useState } from "react"
import { AlertTriangle, Info } from "lucide-react"
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card"
import { Checkbox } from "@/components/ui/checkbox"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select"
import { Separator } from "@/components/ui/separator"
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table"
import { useAppState } from "@/components/app/app-state"
import { Field } from "@/components/app/field"
import { MoneyInput } from "@/components/app/money-input"
import { PageHeader } from "@/components/app/page-header"
import { BalanceChart } from "@/components/charts/balance-chart"
import { Legend } from "@/components/charts/chart-tooltip"
import { buildForecast, type Adjustment } from "@/lib/forecast"
import { addDays, fmt, parseAmount, sum } from "@/lib/format"
import { negativeRuns, type NegativeRun } from "@/lib/runs"
import { cn } from "@/lib/utils"

export default function ScenariosPage() {
  const { forecastInputs: inputs, forecast } = useAppState()
  const forecastInputs = inputs!
  const options = forecast.days.slice(0, 35).filter((d) => d.receipts > 0)
  const [delayOn, setDelayOn] = useState(true)
  const [day, setDay] = useState(options[0]?.date ?? "")
  const [delay, setDelay] = useState("5")
  const [expOn, setExpOn] = useState(false)
  const [expDesc, setExpDesc] = useState("Emergency generator repair")
  const [expAmt, setExpAmt] = useState("3,500,000.00")
  const [expDate, setExpDate] = useState("2026-10-21")

  const result = useMemo(() => {
    const adj: Adjustment[] = []
    const notes: string[] = []
    if (delayOn) {
      const amt = forecast.days.find((x) => x.date === day)?.receipts ?? 0
      const toDate = addDays(day, Number(delay))
      adj.push({ date: day, inflow: -amt }, { date: toDate, inflow: amt })
      notes.push(`${fmt.lkr0(amt)} of receipts expected ${fmt.day(day)} arrives ${fmt.day(toDate)}`)
    }
    const amount = parseAmount(expAmt)
    if (expOn && amount && amount > 0 && expDate) {
      adj.push({ date: expDate, outflow: amount })
      notes.push(`${expDesc || "Expense"}: −${fmt.lkr0(amount)} on ${fmt.day(expDate)}`)
    }
    const sc = buildForecast({ ...forecastInputs, adjustments: adj })
    const days = sc.days.map((d, i) => ({ ...d, baseline: forecast.days[i].closing }))
    return { days, notes }
  }, [delayOn, day, delay, expOn, expDesc, expAmt, expDate, forecastInputs, forecast])

  const baseRuns = negativeRuns(forecast.days.map((d) => ({ date: d.date, value: d.closing })))
  const scRuns = negativeRuns(result.days.map((d) => ({ date: d.date, value: d.closing })))
  const daysNeg = (rs: NegativeRun[]) => sum(rs, (r) => r.days)
  const lowB = forecast.days.reduce((m, d) => (d.closing < m.closing ? d : m))
  const lowS = result.days.reduce((m, d) => (d.closing < m.closing ? d : m))
  const worse = daysNeg(scRuns) > daysNeg(baseRuns)
  const newRuns = scRuns.filter((r) => !baseRuns.some((b) => b.start <= r.end && r.start <= b.end))
  const verdict = !result.notes.length
    ? "Turn on a change to see its effect."
    : worse
      ? `This scenario adds ${daysNeg(scRuns) - daysNeg(baseRuns)} day(s) below zero${newRuns.length ? `, including a new shortage from ${fmt.day(newRuns[0].start)}` : ""}.`
      : daysNeg(scRuns) < daysNeg(baseRuns)
        ? "This scenario reduces days below zero."
        : `No change to shortage days. Lowest balance moves by ${fmt.lkr0(lowS.closing - lowB.closing)}.`

  const reset = () => { setDelayOn(true); setDay(options[0]?.date ?? ""); setDelay("5"); setExpOn(false) }

  return (
    <>
      <PageHeader title="Scenario Analysis" description="Test “what if” changes against the current forecast. Scenarios are not saved and never change the real forecast." />
      <div className="grid items-start gap-4 xl:grid-cols-[380px_minmax(0,1fr)]">
        <Card>
          <CardHeader><CardTitle>Scenario</CardTitle><CardDescription>Turn on one or both changes</CardDescription></CardHeader>
          <CardContent className="grid gap-4">
            <div className="grid gap-3">
              <div className="flex items-center gap-2"><Checkbox id="delay-on" checked={delayOn} onCheckedChange={(v) => setDelayOn(v === true)} /><Label htmlFor="delay-on">Delay an expected receipt</Label></div>
              <Field label="Expected receipt" help="Sales receipts expected in the current account that day">
                <Select value={day} onValueChange={setDay} disabled={!delayOn}>
                  <SelectTrigger className="w-full"><SelectValue /></SelectTrigger>
                  <SelectContent>{options.map((d) => <SelectItem key={d.date} value={d.date}>{fmt.day(d.date)} — {fmt.lkr0(d.receipts)}</SelectItem>)}</SelectContent>
                </Select>
              </Field>
              <Field label="Delay by">
                <Select value={delay} onValueChange={setDelay} disabled={!delayOn}>
                  <SelectTrigger className="w-full"><SelectValue /></SelectTrigger>
                  <SelectContent>{[1, 2, 3, 5, 7, 10, 14].map((n) => <SelectItem key={n} value={String(n)}>{n} {n > 1 ? "days" : "day"}</SelectItem>)}</SelectContent>
                </Select>
              </Field>
            </div>
            <Separator />
            <div className="grid gap-3">
              <div className="flex items-center gap-2"><Checkbox id="exp-on" checked={expOn} onCheckedChange={(v) => setExpOn(v === true)} /><Label htmlFor="exp-on">Add a future expense</Label></div>
              <Field label="Description" htmlFor="exp-desc"><Input id="exp-desc" value={expDesc} onChange={(e) => setExpDesc(e.target.value)} disabled={!expOn} /></Field>
              <div className="grid gap-3 sm:grid-cols-2">
                <Field label="Amount" htmlFor="exp-amt"><MoneyInput id="exp-amt" value={expAmt} onChange={setExpAmt} disabled={!expOn} /></Field>
                <Field label="Date it leaves the account" htmlFor="exp-date">
                  <Input id="exp-date" type="date" min={forecast.days[0].date} max={forecast.days.at(-1)!.date} value={expDate} onChange={(e) => setExpDate(e.target.value)} disabled={!expOn} />
                </Field>
              </div>
            </div>
            <Separator />
            <div><Button variant="outline" onClick={reset}>Reset</Button></div>
          </CardContent>
        </Card>

        <div className="grid gap-4">
          <Alert variant={result.notes.length && worse ? "destructive" : "info"}>
            {result.notes.length && worse ? <AlertTriangle /> : <Info />}
            <AlertTitle>{verdict}</AlertTitle>
            <AlertDescription>{result.notes.length ? result.notes.join(" · ") : "Baseline is the current forecast."}</AlertDescription>
          </Alert>
          <Card>
            <CardHeader><CardTitle>Effect on projected cash</CardTitle><CardDescription>Baseline forecast vs. this scenario, current account</CardDescription></CardHeader>
            <CardContent>
              <div className="grid overflow-hidden rounded-md border sm:grid-cols-2">
                <Outcome title="Baseline" low={lowB.closing} lowDate={lowB.date} runs={baseRuns} />
                <div className="border-t sm:border-t-0 sm:border-l"><Outcome title="Scenario" low={lowS.closing} lowDate={lowS.date} runs={scRuns} /></div>
              </div>
            </CardContent>
          </Card>
          <Card>
            <CardHeader><CardTitle>Projected daily balance</CardTitle></CardHeader>
            <CardContent className="grid gap-2">
              <Legend items={[{ label: "Scenario", color: "var(--chart-4)", kind: "line" }, { label: "Baseline", color: "var(--muted-foreground)", kind: "dashed" }]} />
              <BalanceChart days={result.days} height={240} showBand={false} showBaseline />
            </CardContent>
          </Card>
          <Card>
            <CardHeader><CardTitle>Cash-shortage dates in this scenario</CardTitle></CardHeader>
            <Table>
              <TableHeader><TableRow><TableHead>Shortage period</TableHead><TableHead className="text-right">Days</TableHead><TableHead className="text-right">Worst balance</TableHead><TableHead>Compared with baseline</TableHead></TableRow></TableHeader>
              <TableBody>
                {scRuns.length === 0 && <TableRow><TableCell colSpan={4} className="text-muted-foreground py-8 text-center">No shortages in this scenario.</TableCell></TableRow>}
                {scRuns.map((r) => {
                  const b = baseRuns.find((x) => x.start <= r.end && r.start <= x.end)
                  return (
                    <TableRow key={r.start} className="[&>td:first-child]:shadow-[inset_3px_0_0_var(--destructive)]">
                      <TableCell className="font-semibold">{r.start === r.end ? fmt.day(r.start) : `${fmt.day(r.start)} – ${fmt.day(r.end)}`}</TableCell>
                      <TableCell className="text-right tabular">{r.days}</TableCell>
                      <TableCell className="text-destructive text-right tabular">{fmt.lkr0(r.min)}</TableCell>
                      <TableCell className="text-muted-foreground">
                        {!b ? <Badge variant="destructive-soft">New shortage</Badge> : Math.abs(r.min - b.min) < 1 ? "Unchanged" : r.min < b.min ? `Worse by ${fmt.lkr0(b.min - r.min)}` : `Better by ${fmt.lkr0(r.min - b.min)}`}
                      </TableCell>
                    </TableRow>
                  )
                })}
              </TableBody>
            </Table>
          </Card>
        </div>
      </div>
    </>
  )
}

function Outcome({ title, low, lowDate, runs }: { title: string; low: number; lowDate: string; runs: NegativeRun[] }) {
  const days = sum(runs, (r) => r.days)
  return (
    <div className="grid content-start gap-2 p-3.5">
      <h4 className="text-muted-foreground text-xs font-semibold">{title}</h4>
      <div>
        <div className="text-muted-foreground text-xs">Lowest balance</div>
        <div className={cn("text-lg font-semibold tabular", low < 0 && "text-destructive")}>{fmt.lkr0(low)}</div>
        <div className="text-muted-foreground text-xs">on {fmt.day(lowDate)}</div>
      </div>
      <dl className="grid grid-cols-[1fr_auto] gap-x-4 gap-y-1 text-[13px]">
        <dt className="text-muted-foreground">Shortage periods</dt><dd className="font-medium">{runs.length}</dd>
        <dt className="text-muted-foreground">Days below zero</dt><dd className={cn("font-medium", days > 0 && "text-destructive")}>{days}</dd>
        <dt className="text-muted-foreground">First shortage</dt><dd className="font-medium">{runs[0] ? fmt.day(runs[0].start) : "None"}</dd>
      </dl>
    </div>
  )
}
