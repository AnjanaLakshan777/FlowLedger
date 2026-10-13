import { fmt } from "@/lib/format"
import { cn } from "@/lib/utils"

/**
 * One rule for every forecast balance: zero or above is an "Expected closing balance"; below zero it is a "Cash Shortage"
 * shown as a positive amount — never as a negative balance next to a separate shortage figure.
 */
export const balanceLabel = (v: number) => (v < 0 ? "Cash Shortage" : "Expected closing balance")
/** "LKR 1,234" for either case (the shortage amount is the size of the negative balance). */
export const balanceAmount = (v: number) => fmt.lkr0(Math.abs(v))

/** Table cell: the amount, or "Cash Shortage" above the amount in red when below zero. */
export function ForecastBalance({ value, className }: { value: number; className?: string }) {
  if (value >= 0) return <span className={className}>{fmt.amt(value)}</span>
  // label above the amount keeps table columns narrow
  return (
    <span className={cn("text-destructive inline-grid justify-items-end font-semibold", className)}>
      <span className="text-[11px] font-medium">Cash Shortage</span>
      <span className="whitespace-nowrap">{fmt.amt(-value)}</span>
    </span>
  )
}
