import type { ReactNode } from "react"
import { cn } from "@/lib/utils"

export function StateDot({ tone, children }: { tone: "ok" | "error" | "idle"; children: ReactNode }) {
  return (
    <span className={cn("inline-flex items-center gap-1.5 text-[13px] whitespace-nowrap", tone === "error" && "text-destructive")}>
      <span className={cn("size-1.5 shrink-0 rounded-full", tone === "ok" ? "bg-chart-3" : tone === "error" ? "bg-destructive" : "bg-muted-foreground")} />
      {children}
    </span>
  )
}
