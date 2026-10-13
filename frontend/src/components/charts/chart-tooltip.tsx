import type { ReactNode } from "react"
import { cn } from "@/lib/utils"

export interface TipRow { color?: string; label: string; value: string; negative?: boolean }

export function ChartTooltipBox({ title, rows }: { title: ReactNode; rows: TipRow[] }) {
  return (
    <div className="bg-popover text-popover-foreground min-w-44 rounded-md border px-2.5 py-2 text-xs shadow-md">
      <div className="text-muted-foreground mb-1">{title}</div>
      {rows.map((r) => (
        <div key={r.label} className="flex items-center justify-between gap-4 tabular">
          <span className="text-muted-foreground inline-flex items-center gap-1.5">
            {r.color && <i className="inline-block size-2 rounded-[2px]" style={{ background: r.color }} />}
            {r.label}
          </span>
          <b className={cn("font-medium", r.negative && "text-destructive")}>{r.value}</b>
        </div>
      ))}
    </div>
  )
}

export function Legend({ items }: { items: { label: string; color: string; kind?: "box" | "line" | "dot" | "dashed" | "band" }[] }) {
  return (
    <div className="text-muted-foreground flex flex-wrap gap-x-4 gap-y-1 text-xs">
      {items.map((it) => (
        <span key={it.label} className="inline-flex items-center gap-1.5">
          {it.kind === "line" || it.kind === "dashed" ? (
            <i className="inline-block h-0 w-3.5" style={{ borderTop: `2px ${it.kind === "dashed" ? "dashed" : "solid"} ${it.color}` }} />
          ) : (
            <i className={cn("inline-block size-2.5", it.kind === "dot" ? "rounded-full" : "rounded-[2px]", it.kind === "band" && "opacity-40")} style={{ background: it.color }} />
          )}
          {it.label}
        </span>
      ))}
    </div>
  )
}
