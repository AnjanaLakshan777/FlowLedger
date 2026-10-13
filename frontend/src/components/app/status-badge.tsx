import { AlertTriangle, Check, XCircle } from "lucide-react"
import { Badge } from "@/components/ui/badge"

type Variant = "default" | "secondary" | "destructive" | "outline" | "soft" | "destructive-soft" | "attention" | "dashed" | "muted"

const MAP: Record<string, { variant: Variant; label?: string; icon?: "check" | "alert" | "x" }> = {
  // bank transactions
  Confirmed: { variant: "soft", icon: "check" },
  Excluded: { variant: "muted", label: "Excluded (duplicate)" },
  Expected: { variant: "outline", label: "Expected" },
  // cheques (MVP: future payments, assumed paid on their expected clearing date)
  Scheduled: { variant: "outline" },
  "Assumed paid": { variant: "muted" },
  Cancelled: { variant: "muted" },
  Bounced: { variant: "destructive", icon: "x" },
  // general
  Synced: { variant: "soft", icon: "check" },
  Error: { variant: "destructive-soft", icon: "x" },
  Valid: { variant: "soft" },
  Warning: { variant: "attention", icon: "alert" },
  Active: { variant: "soft" },
  Invited: { variant: "dashed" },
  Deactivated: { variant: "muted" },
  Backdated: { variant: "outline" },
  "Awaiting approval": { variant: "attention" },
  Approved: { variant: "soft", icon: "check" },
  Rejected: { variant: "muted" },
}
const ICONS = { check: Check, alert: AlertTriangle, x: XCircle }

export function StatusBadge({ status, label }: { status: string; label?: string }) {
  const m = MAP[status] ?? { variant: "outline" as Variant }
  const Icon = m.icon ? ICONS[m.icon] : null
  return (
    <Badge variant={m.variant}>
      {Icon && <Icon />}
      {label ?? m.label ?? status}
    </Badge>
  )
}

/** Bank status of a transaction: duplicates are not part of the Actual Bank Balance. */
export const bankStatus = (s: string) => (s === "Excluded" ? "Excluded" : "Confirmed")
