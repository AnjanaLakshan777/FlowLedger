import { BarChart3, Banknote, FileText, Landmark, LayoutDashboard, TrendingUp, Upload, Users, type LucideIcon } from "lucide-react"
import type { Role } from "@/data/types"
import { canAccessPage, type PageId } from "@/lib/permissions"

// Scenario Analysis is deferred (src/pages/scenarios.tsx is kept but not routed).
export interface NavItem { id: PageId; label: string; path: string; icon: LucideIcon }
export const NAV: { group: string; items: NavItem[] }[] = [
  { group: "Overview", items: [{ id: "dashboard", label: "Dashboard", path: "/dashboard", icon: LayoutDashboard }] },
  {
    group: "Sales",
    items: [
      { id: "daily-sales", label: "Daily Sales", path: "/daily-sales", icon: Banknote },
      { id: "historical-import", label: "Historical Sales Import", path: "/historical-import", icon: Upload },
    ],
  },
  {
    group: "Banking",
    items: [
      { id: "bank-account", label: "Bank Account", path: "/bank-account", icon: Landmark },
      { id: "cheques", label: "Cheque Payments", path: "/cheques", icon: FileText },
    ],
  },
  {
    group: "Planning",
    items: [
      { id: "forecast", label: "Cash-Flow Forecast", path: "/forecast", icon: TrendingUp },
      { id: "forecast-vs-actual", label: "Forecast vs Actual", path: "/forecast-vs-actual", icon: BarChart3 },
    ],
  },
  {
    group: "Administration",
    items: [
      { id: "users-settings", label: "Users & Settings", path: "/users-settings", icon: Users },
    ],
  },
]
export const NAV_ITEMS = NAV.flatMap((g) => g.items.map((i) => ({ ...i, group: g.group })))

/** Navigation for a role: pages it can't open are hidden, and so are groups left empty. */
export const visibleNav = (role: Role | null) =>
  NAV.map((g) => ({ ...g, items: g.items.filter((i) => canAccessPage(role, i.id)) })).filter((g) => g.items.length > 0)
