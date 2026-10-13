import type { LucideIcon } from "lucide-react"
import type { ReactNode } from "react"
import { cn } from "@/lib/utils"
import { fmt } from "@/lib/format"

/** Headline figure. `forecast` cards use a dashed border so expected figures never look confirmed. */
export function KpiCard({ label, icon: Icon, amount, value, sub, forecast, negative }: {
  label: string; icon?: LucideIcon; amount?: number; value?: ReactNode; sub?: ReactNode; forecast?: boolean; negative?: boolean
}) {
  return (
    <div className={cn("grid content-start gap-1.5 rounded-lg border px-4 py-3.5", forecast ? "border-dashed" : "bg-card shadow-xs")}>
      <div className="text-muted-foreground flex items-center gap-1.5 text-[13px]">
        {Icon && <Icon className="size-3.5" />}
        {label}
      </div>
      <div className={cn("truncate text-[22px] leading-tight font-semibold tracking-tight tabular", negative && "text-destructive")}>
        {amount !== undefined ? (
          <>
            <span className="text-muted-foreground mr-1 text-xs font-medium tracking-normal">LKR</span>
            {fmt.amt(amount)}
          </>
        ) : value}
      </div>
      {sub && <div className="text-muted-foreground text-xs">{sub}</div>}
    </div>
  )
}

export function StatRow({ items }: { items: { label: string; value: ReactNode; sub?: ReactNode; negative?: boolean }[] }) {
  return (
    <div className="bg-card grid grid-cols-2 overflow-hidden rounded-md border sm:grid-cols-[repeat(auto-fit,minmax(130px,1fr))]">
      {items.map((it, i) => (
        <div key={i} className="grid gap-0.5 border-t border-l px-3 py-2.5 first:border-l-0 sm:border-t-0 [&:nth-child(-n+2)]:border-t-0">
          <span className="text-muted-foreground text-xs">{it.label}</span>
          <b className={cn("text-[17px] font-semibold tabular", it.negative && "text-destructive")}>{it.value}</b>
          {it.sub && <span className="text-muted-foreground text-xs">{it.sub}</span>}
        </div>
      ))}
    </div>
  )
}
