import type { ReactNode } from "react"

export function PageHeader({ title, description, actions }: { title: ReactNode; description?: ReactNode; actions?: ReactNode }) {
  return (
    <div className="flex flex-wrap items-end justify-between gap-4">
      <div className="min-w-0">
        <h1 className="text-[22px] leading-tight font-semibold tracking-tight">{title}</h1>
        {description && <p className="text-muted-foreground mt-1 max-w-[72ch] text-sm">{description}</p>}
      </div>
      {actions && <div className="flex flex-wrap items-center gap-2">{actions}</div>}
    </div>
  )
}

export function SectionLabel({ badge, title, hint }: { badge: ReactNode; title: ReactNode; hint?: ReactNode }) {
  return (
    <div className="-mb-1 flex flex-wrap items-center gap-2 text-[13px] font-semibold">
      {badge}
      {title}
      {hint && <span className="text-muted-foreground font-normal">· {hint}</span>}
    </div>
  )
}
