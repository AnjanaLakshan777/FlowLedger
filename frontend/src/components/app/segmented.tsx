import { cn } from "@/lib/utils"

/** Segmented control (shadcn TabsList look) for switching views and filters. */
export function Segmented<T extends string>({ options, value, onChange, className }: {
  options: { value: T; label: string; count?: number | string }[]; value: T; onChange: (v: T) => void; className?: string
}) {
  return (
    <div role="group" className={cn("bg-muted text-muted-foreground inline-flex max-w-full flex-wrap items-center gap-0.5 rounded-lg p-[3px]", className)}>
      {options.map((o) => (
        <button
          key={o.value}
          type="button"
          aria-pressed={o.value === value}
          onClick={() => onChange(o.value)}
          className={cn(
            "focus-visible:ring-ring/50 inline-flex h-7 items-center gap-1.5 rounded-md px-3 text-[13px] font-medium whitespace-nowrap outline-none focus-visible:ring-[3px]",
            o.value === value ? "bg-background text-foreground dark:bg-input/40 shadow-sm" : "hover:text-foreground"
          )}
        >
          {o.label}
          {o.count !== undefined && <span className="text-muted-foreground text-[11px] tabular">{o.count}</span>}
        </button>
      ))}
    </div>
  )
}
