import { useState, type KeyboardEventHandler } from "react"
import { Input } from "@/components/ui/input"
import { parseAmount } from "@/lib/format"
import { cn } from "@/lib/utils"

const nf2 = new Intl.NumberFormat("en-US", { minimumFractionDigits: 2, maximumFractionDigits: 2 })

/** LKR amount input: right-aligned, formats with separators on blur. */
export function MoneyInput({ value, onChange, id, invalid, disabled, className, ...rest }: {
  value: string; onChange: (v: string) => void; id?: string; invalid?: boolean; disabled?: boolean; className?: string
  onKeyDown?: KeyboardEventHandler<HTMLInputElement>; "aria-label"?: string
}) {
  const [focused, setFocused] = useState(false)
  return (
    <div className="relative">
      <span className="text-muted-foreground pointer-events-none absolute top-1/2 left-3 -translate-y-1/2 text-xs font-medium">LKR</span>
      <Input
        id={id}
        inputMode="decimal"
        autoComplete="off"
        placeholder="0.00"
        disabled={disabled}
        aria-invalid={invalid || undefined}
        className={cn("pl-12 text-right tabular", className)}
        value={value}
        onFocus={(e) => {
          setFocused(true)
          const v = parseAmount(value)
          if (v != null && !Number.isNaN(v)) onChange(String(v))
          requestAnimationFrame(() => e.target.select())
        }}
        onBlur={() => {
          setFocused(false)
          const v = parseAmount(value)
          if (v != null && !Number.isNaN(v)) onChange(nf2.format(v))
        }}
        onChange={(e) => onChange(e.target.value)}
        data-focused={focused || undefined}
        {...rest}
      />
    </div>
  )
}
