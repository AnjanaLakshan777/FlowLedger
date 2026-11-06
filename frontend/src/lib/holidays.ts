import type { ApiHoliday } from "@/lib/api/flowledger"

/** Holidays by date; one date can have several (Vesak and May Day in 2026). */
export function byDate(holidays: ApiHoliday[]): Map<string, ApiHoliday[]> {
  const map = new Map<string, ApiHoliday[]>()
  for (const h of holidays) map.set(h.date, [...(map.get(h.date) ?? []), h])
  return map
}

/** The Central Bank's codes as words, for the day details: "Bank holiday", "Public holiday", "Mercantile holiday", "Poya day". */
export function holidayKinds(h: ApiHoliday): string[] {
  return [
    h.bankHoliday && "Bank holiday",
    h.publicHoliday && "Public holiday",
    h.mercantileHoliday && "Mercantile holiday",
    h.poyaDay && "Poya day",
  ].filter(Boolean) as string[]
}

/** True when banks are closed on the date: a listed bank holiday. Saturdays and Sundays are bank holidays too but not listed. */
export const isBankHoliday = (holidays: ApiHoliday[] | undefined) => !!holidays?.some((h) => h.bankHoliday)

/** The holiday CSV template: the Central Bank's list copied as date,name,codes. */
export const HOLIDAY_TEMPLATE_CSV = [
  "date,name,codes",
  "2027-01-15,Tamil Thai Pongal Day,B. P. M.",
  "2027-01-22,Duruthu Full Moon Poya Day,B. P.",
  "2027-10-28,Deepawali Festival Day,B. P.",
  "2027-12-25,Christmas Day,B. P. M.",
].join("\n") + "\n"
