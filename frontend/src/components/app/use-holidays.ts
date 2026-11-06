import { useEffect, useState } from "react"
import { flowledger, type ApiHoliday } from "@/lib/api/flowledger"
import { addDays, daysBetween } from "@/lib/format"
import { byDate } from "@/lib/holidays"
import { useAppState } from "./app-state"

const MAX_DAYS = 790

/**
 * Sri Lankan holidays between from and to (live data only). The calendar works without them, so a failed request
 * leaves the map empty instead of showing an error.
 */
export function useHolidays(from: string | undefined, to: string | undefined): Map<string, ApiHoliday[]> {
  const { company, mode } = useAppState()
  const [holidays, setHolidays] = useState<Map<string, ApiHoliday[]>>(new Map())
  const cid = company?.id
  useEffect(() => {
    if (mode !== "live" || !cid || !from || !to) return
    let current = true
    const start = daysBetween(from, to) > MAX_DAYS ? addDays(to, -MAX_DAYS) : from
    flowledger.holidays(cid, start, to)
      .then((rows) => { if (current) setHolidays(byDate(rows)) })
      .catch(() => { if (current) setHolidays(new Map()) })
    return () => { current = false }
  }, [mode, cid, from, to])
  return holidays
}
