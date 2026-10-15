import { Link } from "react-router-dom"
import { AlertTriangle, Banknote, Check, CheckCircle2, Download, FileText, Plus, TrendingUp, Upload, Wallet } from "lucide-react"
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Card, CardAction, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card"
import { useAppState } from "@/components/app/app-state"
import { CellStack, DataTable } from "@/components/app/data-table"
import { DataState, useNotReady } from "@/components/app/data-state"
import { KpiCard } from "@/components/app/kpi-card"
import { PageHeader, SectionLabel } from "@/components/app/page-header"
import { balanceLabel } from "@/components/app/forecast-balance"
import { ShortageAlerts } from "@/components/app/shortage-alerts"
import { StateDot } from "@/components/app/state-dot"
import { bankStatus, StatusBadge } from "@/components/app/status-badge"
import { WeeklyBalanceChart } from "@/components/charts/bar-charts"
import { Legend } from "@/components/charts/chart-tooltip"
import type { BankTransaction, Cheque } from "@/data/types"
import { isUpcoming } from "@/lib/cheques"
import { FORECAST_WEEKS } from "@/lib/forecast"
import { addDays, daysBetween, fmt } from "@/lib/format"

export default function DashboardPage() {
  const { kpis, forecast, cheques, transactions, bankAccount: acct, allow, today: TODAY, mode, forecastInfo } = useAppState()
  const live = mode === "live"
  const run = forecastInfo?.run
  const horizon = live ? "84 days" : `${FORECAST_WEEKS} weeks`
  const notReady = useNotReady(["bank", "cheques", "forecast"])
  const balanceText = kpis.actualBalance == null ? "No confirmed bank balance yet." : `Actual Bank Balance ${fmt.lkr0(kpis.actualBalance)}.`
  const first = forecast.shortages[0]
  const shortDays = forecast.days.filter((d) => d.closing < 0).length
  const upcoming = cheques.filter((c) => isUpcoming(c, TODAY)).sort((a, b) => a.expected_clear_date.localeCompare(b.expected_clear_date)).slice(0, 7)
  const recent = transactions.slice(0, 7)

  if (live && notReady) {
    return (
      <>
        <PageHeader title="Dashboard" />
        <DataState resources={["bank", "cheques", "forecast"]} what="Dashboard data" />
      </>
    )
  }

  return (
    <>
      <PageHeader
        title="Dashboard"
        description={
          first ? (
            <>
              {balanceText}{" "}
              <b className="text-destructive">Cash Shortage expected on {shortDays} {shortDays > 1 ? "days" : "day"}</b> in the {live ? "forecast" : `next ${horizon}`} — the first on{" "}
              <b className="text-foreground">{fmt.day(first.first_date)}</b>, {daysBetween(TODAY, first.first_date) >= 0 ? `${daysBetween(TODAY, first.first_date)} days away` : "already passed"}.
            </>
          ) : live && !run ? (
            <>{balanceText} No forecast has been made yet.</>
          ) : (
            <>{balanceText} No cash shortages expected in the next {horizon}.</>
          )
        }
        actions={
          <>
            {allow("sales.enter") && <Button variant="outline" asChild><Link to="/daily-sales"><Banknote />Enter daily sales</Link></Button>}
            {allow("cheques.manage") && <Button asChild><Link to="/cheques?new=1"><Plus />Add cheque payment</Link></Button>}
          </>
        }
      />

      {run?.balanceStale && (
        <Alert variant="destructive">
          <AlertTriangle />
          <AlertTitle>The forecast starts from an out-of-date balance</AlertTitle>
          <AlertDescription>The latest confirmed bank balance is {run.dataQuality.balanceAgeDays} days old. Import a recent bank statement.</AlertDescription>
        </Alert>
      )}
      {!acct && (
        <Alert>
          <AlertTriangle />
          <AlertTitle>No bank account set up</AlertTitle>
          <AlertDescription>{live ? "A company admin" : "A System Admin"} needs to configure the company current account before the Actual Bank Balance can be shown.</AlertDescription>
        </Alert>
      )}
      {kpis.syncFailed && acct && (
        <Alert variant="destructive">
          <AlertTriangle />
          <AlertTitle>{live ? "The latest bank statement was rejected" : "Bank data may be out of date"}</AlertTitle>
          <AlertDescription>{live ? acct.error : `Last successful sync ${fmt.dateTimeFull(acct.last_sync)}. The Actual Bank Balance shown is the last one received from the bank.`}</AlertDescription>
        </Alert>
      )}

      {forecast.shortages.length > 0 ? (
        <ShortageAlerts days={forecast.days} action={<Button variant="outline" size="sm" asChild><Link to="/forecast">View in forecast</Link></Button>} />
      ) : (
        live && !run
          ? <Card className="flex-row items-center gap-2 px-4 text-sm"><TrendingUp className="text-muted-foreground size-4" />No forecast yet. <Link to="/forecast" className="text-primary font-medium hover:underline">Open the forecast</Link></Card>
          : <Card className="flex-row items-center gap-2 px-4 text-sm"><CheckCircle2 className="text-primary size-4" />No cash shortages expected in the next {horizon}.</Card>
      )}

      <SectionLabel badge={<Badge variant="soft"><Check />Actual</Badge>} title="Confirmed by the bank" hint="never includes expected receipts" />
      <div className="grid gap-4 sm:grid-cols-3">
        {kpis.actualBalance == null
          ? <KpiCard label="Actual Bank Balance" icon={Wallet} value="Not confirmed yet" sub="Import a bank statement" />
          : <KpiCard label="Actual Bank Balance" icon={Wallet} amount={kpis.actualBalance} sub={live && acct?.balance_as_of ? `As of ${fmt.dateTimeFull(acct.balance_as_of)}` : "Current account"} />}
        <KpiCard label="Today's confirmed receipts" icon={Download} amount={kpis.receiptsToday} sub={`${kpis.receiptsCount} bank ${kpis.receiptsCount === 1 ? "credit" : "credits"}`} />
        <KpiCard label="Today's confirmed payments" icon={Upload} amount={kpis.paymentsToday} sub={`${kpis.paymentsCount} bank ${kpis.paymentsCount === 1 ? "debit" : "debits"}`} />
      </div>

      <div className="grid gap-4 xl:grid-cols-2">
        <Card>
          <CardHeader>
            <CardTitle>Bank account</CardTitle>
            <CardDescription>Company current account and bank sync status</CardDescription>
            <CardAction><Button variant="outline" size="sm" asChild><Link to="/bank-account">Bank account</Link></Button></CardAction>
          </CardHeader>
          <CardContent>
            {acct ? (
              <dl className="grid grid-cols-[minmax(130px,40%)_1fr] gap-x-4 gap-y-2 text-[13px]">
                {([
                  ["Bank", acct.bank], ["Account", <>{acct.account_name} · <span className="font-mono">{acct.account_masked}</span></>], ["Type", "Current Account"],
                  ["Actual Bank Balance", <span className="tabular">{fmt.lkr(acct.balance)}</span>],
                  ...(live
                    ? [["Last statement import", <StateDot tone={kpis.syncFailed ? "error" : "ok"}>{acct.last_sync ? fmt.dateTimeFull(acct.last_sync) : "None yet"}</StateDot>]] as const
                    : [["Last successful sync", <StateDot tone={kpis.syncFailed ? "error" : "ok"}>{fmt.dateTimeFull(acct.last_sync)}</StateDot>], ["Sync status", kpis.syncFailed ? "Failed" : "Synced"]] as const),
                ] as const).map(([k, v]) => (
                  <div key={k} className="contents"><dt className="text-muted-foreground">{k}</dt><dd className="font-medium">{v}</dd></div>
                ))}
              </dl>
            ) : <p className="text-muted-foreground text-sm">No bank account set up yet.</p>}
          </CardContent>
        </Card>
        <Card>
          <CardHeader>
            <CardTitle>Recent bank transactions</CardTitle>
            <CardDescription>Latest items received from bank data</CardDescription>
            <CardAction><Button variant="outline" size="sm" asChild><Link to="/bank-account">View all</Link></Button></CardAction>
          </CardHeader>
          <TxnMiniTable rows={recent} />
        </Card>
      </div>

      <SectionLabel badge={<Badge variant="dashed"><TrendingUp />Forecast</Badge>} title="Expected, not yet in the bank" hint={run ? `${fmt.date(run.horizonStartDate)} – ${fmt.date(run.horizonEndDate)}` : live ? "no forecast yet" : `next ${FORECAST_WEEKS} weeks, Mon–Sun`} />
      <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-4">
        <KpiCard forecast label="Cheques clearing next 7 days" icon={FileText} amount={kpis.cheques7dAmount} sub={`${kpis.cheques7dCount} cheques · to ${fmt.short(addDays(TODAY, 7))}`} />
        {kpis.forecastReceipts == null
          ? <KpiCard forecast label="Expected receipts" icon={TrendingUp} value={run ? "Not estimated" : "No forecast"} sub={run ? "Too little bank history" : "Cash + card, by current-account arrival date"} />
          : <KpiCard forecast label="Expected receipts" icon={TrendingUp} amount={kpis.forecastReceipts} sub="Cash + card, by current-account arrival date" />}
        {kpis.forecastOutflow == null
          ? <KpiCard forecast label="Expected cheque outflows" icon={FileText} value="No forecast" sub="Scheduled cheques, on their expected clearing dates" />
          : <KpiCard forecast label="Expected cheque outflows" icon={FileText} amount={kpis.forecastOutflow} sub="Scheduled cheques, on their expected clearing dates" />}
        {/* end-of-horizon balance — not repeated elsewhere on the Dashboard (the shortage-day count is only in the summary line) */}
        {kpis.forecastClosing == null
          ? <KpiCard forecast label="Expected closing balance" icon={TrendingUp} value="No forecast" sub="End of the forecast" />
          : <KpiCard forecast label={`${balanceLabel(kpis.forecastClosing)}, ${fmt.date(forecast.weeks.at(-1)?.week_end)}`} icon={kpis.forecastClosing < 0 ? AlertTriangle : TrendingUp}
              amount={Math.abs(kpis.forecastClosing)} negative={kpis.forecastClosing < 0} sub={live ? "End of the forecast" : `End of week ${FORECAST_WEEKS}`} />}
      </div>

      <div className="grid gap-4 xl:grid-cols-2">
        <Card>
          <CardHeader>
            <CardTitle>{live ? "84-day cash flow" : `${FORECAST_WEEKS}-week cash flow`}</CardTitle>
            <CardDescription>
              {live ? "Expected closing balance at the end of each 7-day period from the forecast start" : "Expected closing balance each Sunday"}; the line marks the lowest daily balance.{" "}
              {forecast.weeks.some((w) => w.shortage_days > 0)
                ? <b className="text-destructive font-medium">{forecast.weeks.filter((w) => w.shortage_days > 0).length} of {forecast.weeks.length} weeks run short of cash.</b>
                : "No week runs short of cash."}
            </CardDescription>
            <CardAction><Button variant="outline" size="sm" asChild><Link to="/forecast">Open forecast</Link></Button></CardAction>
          </CardHeader>
          <CardContent className="grid gap-2">
            <Legend items={[
              { label: "Week closing balance", color: "var(--chart-3)" },
              { label: "Lowest daily balance (red when below zero)", color: "var(--foreground)", kind: "line" },
              { label: "Below zero", color: "var(--destructive)", kind: "dashed" },
            ]} />
            {forecast.weeks.length ? <WeeklyBalanceChart weeks={forecast.weeks} /> : <p className="text-muted-foreground py-10 text-center text-sm">No forecast yet.</p>}
          </CardContent>
        </Card>
        <Card>
          <CardHeader>
            <CardTitle>Upcoming cheque payments</CardTitle>
            <CardDescription>Scheduled cheques, forecast on their expected clearing date</CardDescription>
            <CardAction><Button variant="outline" size="sm" asChild><Link to="/cheques">View all</Link></Button></CardAction>
          </CardHeader>
          <DataTable<Cheque>
            rows={upcoming}
            rowKey={(r) => r.cheque_no}
            maxHeight="none"
            empty="No upcoming cheque payments."
            columns={[
              { key: "clears", header: "Expected clearing", cell: (r) => <span className="whitespace-nowrap">{fmt.day(r.expected_clear_date)}</span> },
              { key: "payee", header: "Payee", cell: (r) => <CellStack main={r.payee} sub={`${r.category} · #${r.cheque_no}`} /> },
              { key: "amount", header: "Amount (LKR)", numeric: true, cell: (r) => fmt.amt(r.amount) },
            ]}
          />
        </Card>
      </div>
    </>
  )
}

function TxnMiniTable({ rows }: { rows: BankTransaction[] }) {
  return (
    <DataTable<BankTransaction>
      rows={rows}
      rowKey={(r) => r.txn_id}
      maxHeight="none"
      empty="No bank transactions received yet."
      columns={[
        { key: "date", header: "Date", cell: (r) => <span className="whitespace-nowrap">{fmt.short(r.txn_date)}</span> },
        { key: "desc", header: "Description", cell: (r) => <CellStack main={r.description} sub={<span className="font-mono">{r.reference}</span>} /> },
        { key: "status", header: "Bank status", cell: (r) => <StatusBadge status={bankStatus(r.match_status)} /> },
        { key: "amount", header: "Amount (LKR)", numeric: true, cell: (r) => fmt.signed(r.amount) },
      ]}
    />
  )
}
