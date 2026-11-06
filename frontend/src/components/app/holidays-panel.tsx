import { useCallback, useEffect, useRef, useState } from "react"
import { AlertTriangle, Download, ExternalLink, FileUp, Lock, RefreshCw } from "lucide-react"
import { toast } from "sonner"
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert"
import { Button } from "@/components/ui/button"
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card"
import { Input } from "@/components/ui/input"
import { colomboLocal } from "@/lib/api/adapters"
import { ApiError, errorMessage } from "@/lib/api/client"
import { flowledger, type ApiHolidayYear } from "@/lib/api/flowledger"
import { fmt } from "@/lib/format"
import { HOLIDAY_TEMPLATE_CSV } from "@/lib/holidays"
import { useAppState } from "./app-state"
import { DataTable } from "./data-table"
import { Field } from "./field"
import { LoadingCard } from "./data-state"

const MAX_SHOWN_ERRORS = 20

function downloadTemplate() {
  const url = URL.createObjectURL(new Blob([HOLIDAY_TEMPLATE_CSV], { type: "text/csv" }))
  const a = Object.assign(document.createElement("a"), { href: url, download: "flowledger_holidays_template.csv" })
  a.click()
  URL.revokeObjectURL(url)
}

/**
 * Sri Lankan holidays shown on the cash-flow calendar, one list per year from the Central Bank of Sri Lanka. Every
 * company sees the same list, so only a platform SYSTEM_ADMIN uploads it; everyone else sees which years are loaded.
 */
export function HolidaysPanel() {
  const { company, platformAdmin, today } = useAppState()
  const cid = company?.id ?? null
  const [years, setYears] = useState<ApiHolidayYear[] | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [year, setYear] = useState(String(Number(today.slice(0, 4)) + 1))
  const [uploading, setUploading] = useState(false)
  const [rejected, setRejected] = useState<{ message: string; errors: string[] } | null>(null)
  const input = useRef<HTMLInputElement>(null)

  const load = useCallback(async () => {
    if (!cid) return
    setError(null)
    try {
      setYears(await flowledger.holidayYears(cid))
    } catch (e) {
      setError(errorMessage(e))
    }
  }, [cid])
  useEffect(() => { void load() }, [load])

  async function upload(file: File) {
    if (!cid) return
    const y = Number(year)
    if (!Number.isInteger(y) || y < 2000 || y > 2100) return setRejected({ message: "Enter the year, for example 2027.", errors: [] })
    setUploading(true)
    setRejected(null)
    try {
      const rows = await flowledger.uploadHolidays(cid, y, file)
      toast.success(`Holidays for ${y} imported`, { description: `${fmt.int(rows.length)} holidays; the calendar shows them now` })
      void load()
    } catch (e) {
      const errors = e instanceof ApiError && Array.isArray(e.problem.errors) ? (e.problem.errors as string[]) : []
      setRejected({ message: errorMessage(e), errors })
    } finally {
      setUploading(false)
      if (input.current) input.current.value = ""
    }
  }

  return (
    <div className="grid gap-4">
      <Card>
        <CardHeader>
          <CardTitle>Sri Lankan holidays</CardTitle>
          <CardDescription>
            Bank, public and mercantile holidays from the Central Bank of Sri Lanka, shown on the cash-flow calendar (Poya days, Deepawali,
            Sinhala & Tamil New Year, Christmas and the rest). All Saturdays and Sundays are bank holidays too. Holidays do not change the
            forecast yet.
          </CardDescription>
        </CardHeader>
        <CardContent className="grid gap-3">
          {platformAdmin ? (
            <>
              <p className="text-sm">
                Each year, copy the list from the{" "}
                <a className="text-primary inline-flex items-center gap-1 underline-offset-2 hover:underline" target="_blank" rel="noreferrer"
                  href={`https://www.cbsl.gov.lk/en/about/about-the-bank/bank-holidays-${year || today.slice(0, 4)}`}>
                  Central Bank's Bank Holidays page<ExternalLink className="size-3.5" />
                </a>{" "}
                into the template (date as YYYY-MM-DD, codes B, P, M as listed) and upload it. It replaces that year's whole list.
              </p>
              <div className="flex flex-wrap items-end gap-2">
                <Field label="Year" htmlFor="hol-year">
                  <Input id="hol-year" inputMode="numeric" className="w-28 tabular" value={year} onChange={(e) => setYear(e.target.value.replace(/\D/g, "").slice(0, 4))} />
                </Field>
                <input ref={input} type="file" accept=".csv,text/csv" className="hidden" onChange={(e) => { const f = e.target.files?.[0]; if (f) void upload(f) }} />
                <Button size="sm" disabled={uploading || year.length !== 4} onClick={() => input.current?.click()}><FileUp />{uploading ? "Importing…" : `Upload ${year} holidays`}</Button>
                <Button size="sm" variant="outline" onClick={downloadTemplate}><Download />Template</Button>
              </div>
              {rejected && (
                <Alert variant="destructive">
                  <AlertTriangle />
                  <AlertTitle>The holiday list was not imported</AlertTitle>
                  <AlertDescription>
                    <p>{rejected.message}</p>
                    {rejected.errors.length > 0 && (
                      <ul className="mt-1 list-disc pl-4 text-xs">
                        {rejected.errors.slice(0, MAX_SHOWN_ERRORS).map((err) => <li key={err}>{err}</li>)}
                        {rejected.errors.length > MAX_SHOWN_ERRORS && <li>…and {fmt.int(rejected.errors.length - MAX_SHOWN_ERRORS)} more</li>}
                      </ul>
                    )}
                  </AlertDescription>
                </Alert>
              )}
            </>
          ) : (
            <p className="text-muted-foreground flex items-center gap-1.5 text-sm"><Lock className="size-3.5" />The list is shared by every company; a FlowLedger system administrator updates it each year.</p>
          )}
        </CardContent>
      </Card>

      {error ? (
        <Alert variant="destructive">
          <AlertTriangle /><AlertTitle>The holiday years could not be loaded</AlertTitle>
          <AlertDescription className="grid justify-items-start gap-2">{error}<Button size="sm" variant="outline" onClick={() => void load()}><RefreshCw />Try again</Button></AlertDescription>
        </Alert>
      ) : !years ? <LoadingCard>Loading holiday years…</LoadingCard> : (
        <Card>
          <CardHeader><CardTitle>Years loaded</CardTitle><CardDescription>A year without a list shows no holidays on the calendar.</CardDescription></CardHeader>
          <DataTable<ApiHolidayYear>
            rows={years}
            rowKey={(r) => String(r.year)}
            defaultSort={{ key: "year", dir: "desc" }}
            empty="No holiday lists loaded yet."
            columns={[
              { key: "year", header: "Year", sortValue: (r) => r.year, cell: (r) => <b className="font-semibold tabular">{r.year}</b> },
              { key: "count", header: "Holidays", numeric: true, cell: (r) => fmt.int(r.holidayCount) },
              { key: "source", header: "Source", cell: (r) => r.sourceName },
              { key: "at", header: "Imported", cell: (r) => <span className="whitespace-nowrap">{fmt.dateTime(colomboLocal(r.importedAt))}</span> },
            ]}
          />
        </Card>
      )}
    </div>
  )
}
