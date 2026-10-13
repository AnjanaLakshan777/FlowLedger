import type { ReactNode } from "react"
import { ShieldAlert } from "lucide-react"
import { Link, Navigate } from "react-router-dom"
import { Button } from "@/components/ui/button"
import { Card } from "@/components/ui/card"
import { homePage, ROLE_LABEL } from "@/lib/permissions"
import { useAppState, type PageId } from "./app-state"
import { NAV_ITEMS } from "./nav"

/** Renders the page only for roles allowed to open it; everyone else sees Access denied. */
export function RequireAccess({ page, children }: { page: PageId; children: ReactNode }) {
  const { canPage } = useAppState()
  return canPage(page) ? <>{children}</> : <AccessDenied page={page} />
}

export function AccessDenied({ page }: { page?: PageId }) {
  const { role } = useAppState()
  const home = homePage(role)
  const label = NAV_ITEMS.find((n) => n.id === page)?.label
  return (
    <Card className="items-center gap-3 px-4 py-12 text-center" role="alert">
      <ShieldAlert className="text-muted-foreground size-7" />
      <h1 className="text-[17px] font-semibold">Access denied</h1>
      <p className="text-muted-foreground max-w-[52ch] text-sm">
        {label ? `${label} isn't available` : "This page isn't available"} for the {role ? ROLE_LABEL[role] : "current"} role. Ask a System Admin if you need access.
      </p>
      {home && <Button variant="outline" asChild><Link to={`/${home}`}>Go to {NAV_ITEMS.find((n) => n.id === home)?.label}</Link></Button>}
    </Card>
  )
}

/** Sends a signed-in user to the first page their role can open. */
export function HomeRedirect() {
  const { role } = useAppState()
  const home = homePage(role)
  return home ? <Navigate to={`/${home}`} replace /> : <AccessDenied />
}
