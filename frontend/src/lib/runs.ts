export interface NegativeRun { start: string; end: string; min: number; minDate: string; days: number }

/** Group consecutive days with a negative balance into shortage periods. */
export function negativeRuns(series: { date: string; value: number }[]): NegativeRun[] {
  const out: NegativeRun[] = []
  let cur: NegativeRun | null = null
  for (const d of series) {
    if (d.value < 0) {
      if (!cur) cur = { start: d.date, end: d.date, min: d.value, minDate: d.date, days: 0 }
      cur.end = d.date
      cur.days++
      if (d.value < cur.min) { cur.min = d.value; cur.minDate = d.date }
    } else if (cur) {
      out.push(cur)
      cur = null
    }
  }
  if (cur) out.push(cur)
  return out
}
