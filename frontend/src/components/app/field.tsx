import type { ReactNode } from "react"
import { Label } from "@/components/ui/label"
import { cn } from "@/lib/utils"

export function Field({ label, htmlFor, help, error, className, children, labelAside }: {
  label: ReactNode; htmlFor?: string; help?: ReactNode; error?: string | null; className?: string; children: ReactNode; labelAside?: ReactNode
}) {
  return (
    <div className={cn("grid content-start gap-1.5", className)}>
      <div className="flex items-center justify-between gap-2">
        <Label htmlFor={htmlFor} className="text-[13px]">{label}</Label>
        {labelAside}
      </div>
      {children}
      {help && !error && <p className="text-muted-foreground text-xs">{help}</p>}
      {error && <p className="text-destructive text-xs">{error}</p>}
    </div>
  )
}
