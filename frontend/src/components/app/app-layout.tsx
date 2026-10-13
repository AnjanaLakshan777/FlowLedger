import { useState } from "react"
import { Link, NavLink, Outlet, useLocation, useNavigate } from "react-router-dom"
import { AlertTriangle, Bell, ChevronDown, Clock, FlaskConical, Info, LogOut, Menu, Monitor, Moon, Sun, Users, X, XCircle } from "lucide-react"
import { Button } from "@/components/ui/button"
import {
  DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuLabel, DropdownMenuRadioGroup, DropdownMenuRadioItem, DropdownMenuSeparator, DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu"
import { Popover, PopoverContent, PopoverTrigger } from "@/components/ui/popover"
import { settings } from "@/data"
import { fmt, initials } from "@/lib/format"
import { cn } from "@/lib/utils"
import { useAppState } from "./app-state"
import { Logo } from "./logo"
import { homePage, ROLE_LABEL } from "@/lib/permissions"
import type { PageId } from "./app-state"
import { NAV_ITEMS, visibleNav } from "./nav"
import { StateDot } from "./state-dot"
import { useTheme } from "./theme-provider"

function Sidebar({ onNavigate }: { onNavigate?: () => void }) {
  const { role, canPage, kpis, forecast, imports } = useAppState()
  const awaiting = imports.filter((i) => i.status === "Awaiting approval").length
  const counts: Partial<Record<string, { n: number; critical?: boolean; label: string }>> = {
    forecast: { n: forecast.days.filter((d) => d.closing < 0).length, critical: true, label: "Cash Shortage warnings" },
    "historical-import": { n: awaiting, label: `${awaiting} import${awaiting === 1 ? "" : "s"} awaiting approval` },
  }
  return (
    <div className="bg-sidebar text-sidebar-foreground flex h-full flex-col">
      <div className="border-sidebar-border flex h-14 items-center gap-2.5 border-b px-4">
        <Logo />
        <div className="leading-tight">
          <div className="text-[15px] font-semibold tracking-tight">FlowLedger</div>
          <div className="text-muted-foreground text-[11.5px]">Cash position & forecast</div>
        </div>
      </div>
      <nav aria-label="Main" className="flex-1 overflow-y-auto px-2 pt-1 pb-3">
        {visibleNav(role).map((g) => (
          <div key={g.group}>
            <div className="text-muted-foreground px-2 pt-3 pb-1 text-[11.5px] font-medium">{g.group}</div>
            {g.items.map((it) => {
              const c = counts[it.id]
              return (
                <NavLink
                  key={it.id}
                  to={it.path}
                  onClick={onNavigate}
                  className={({ isActive }) =>
                    cn(
                      "group hover:bg-sidebar-accent hover:text-sidebar-accent-foreground focus-visible:ring-sidebar-ring flex h-[34px] items-center gap-2.5 rounded-md px-2 text-[13.5px] outline-none focus-visible:ring-2",
                      isActive && "bg-sidebar-accent font-semibold [&>svg:first-child]:text-sidebar-primary"
                    )
                  }
                >
                  <it.icon className="size-4" />
                  <span className="truncate">{it.label}</span>
                  {c && c.n > 0 ? (
                    // shortage warnings: a dot, not the day count again (the count is in the Dashboard summary)
                    c.critical
                      ? <span className="bg-destructive ml-auto size-2 rounded-full" role="img" aria-label={c.label} />
                      : <span className="bg-secondary text-secondary-foreground ml-auto grid h-[18px] min-w-5 place-items-center rounded-full px-1.5 text-[11px] font-medium tabular" aria-label={c.label}>{c.n}</span>
                  ) : null}
                </NavLink>
              )
            })}
          </div>
        ))}
      </nav>
      {canPage("bank-account") && <div className="border-sidebar-border text-muted-foreground grid gap-1.5 border-t px-4 py-3 text-xs">
        <StateDot tone={kpis.syncFailed ? "error" : "ok"}>{kpis.syncFailed ? "Bank sync failed" : "Bank synced"}</StateDot>
        <span className="flex items-center gap-1.5"><Clock className="size-3.5" />Last successful sync {fmt.dateTime(kpis.lastSync)}</span>
      </div>}
    </div>
  )
}

const NOTIF_ICON = { critical: AlertTriangle, error: XCircle, warn: AlertTriangle, info: Info }

/** Notifications are worked out from current state (a notification service will replace this). */
function useNotifications() {
  const { forecast, bankAccount, imports, canPage } = useAppState()
  const out: { id: string; level: keyof typeof NOTIF_ICON; title: string; body: string; path: string }[] = []
  const sh = forecast.shortages[0]
  if (sh) out.push({ id: "shortage", level: "critical", title: `Cash Shortage expected ${fmt.day(sh.first_date)}`, body: `Cash Shortage of ${fmt.lkr0(sh.shortage_amount)}${sh.payments[0] ? ` · largest payment ${sh.payments[0].payee}` : ""}.`, path: "/forecast" })
  if (bankAccount?.sync_status === "Error") out.push({ id: "sync", level: "error", title: "Bank sync failed", body: `Showing the Actual Bank Balance from ${fmt.dateTime(bankAccount.last_sync)}.`, path: "/bank-account" })
  const waiting = imports.filter((i) => i.status === "Awaiting approval")
  if (waiting.length) out.push({ id: "import", level: "info", title: "Historical import awaiting approval", body: waiting.map((i) => i.import_id).join(", "), path: "/historical-import" })
  // only notifications about pages this role can open
  return out.filter((n) => canPage(n.path.slice(1) as PageId))
}

export function AppLayout() {
  const { role, currentUser, users, canPage, actions } = useAppState()
  const { theme, setTheme } = useTheme()
  const [mobileOpen, setMobileOpen] = useState(false)
  const [notifOpen, setNotifOpen] = useState(false)
  const location = useLocation()
  const navigate = useNavigate()
  const notifications = useNotifications()
  const page = NAV_ITEMS.find((n) => location.pathname.startsWith(n.path))
  const company = settings.find((s) => s.key === "company_name")?.value
  const ThemeIcon = theme === "light" ? Sun : theme === "dark" ? Moon : Monitor
  if (!currentUser || !role) return null

  return (
    <div className="min-h-svh lg:grid lg:grid-cols-[248px_minmax(0,1fr)]">
      <aside className="border-sidebar-border sticky top-0 hidden h-svh border-r lg:block">
        <Sidebar />
      </aside>
      {/* tablet / mobile drawer */}
      {mobileOpen && (
        <div className="fixed inset-0 z-50 lg:hidden">
          <div className="absolute inset-0 bg-black/40" onClick={() => setMobileOpen(false)} />
          <div className="border-sidebar-border absolute inset-y-0 left-0 w-64 border-r shadow-lg">
            <Sidebar onNavigate={() => setMobileOpen(false)} />
            <Button variant="ghost" size="icon-sm" className="absolute top-3 right-2" onClick={() => setMobileOpen(false)} aria-label="Close navigation"><X /></Button>
          </div>
        </div>
      )}
      <div className="flex min-w-0 flex-col">
        <header className="bg-background/95 supports-[backdrop-filter]:bg-background/80 sticky top-0 z-30 flex h-14 items-center gap-3 border-b px-4 backdrop-blur md:px-6">
          <Button variant="ghost" size="icon" className="lg:hidden" onClick={() => setMobileOpen(true)} aria-label="Open navigation"><Menu /></Button>
          <div className="text-muted-foreground truncate text-[13px]">
            {page?.group} / <span className="text-foreground font-medium">{page?.label}</span>
          </div>
          <div className="flex-1" />
          <div className="hidden h-8 items-center gap-2 rounded-md border px-2.5 text-[13px] whitespace-nowrap md:flex" title="Company">
            <span className="bg-chart-3 size-2 rounded-[2px]" />
            {company}
          </div>
          <DropdownMenu>
            <DropdownMenuTrigger asChild>
              <Button variant="ghost" size="icon" aria-label="Theme"><ThemeIcon /></Button>
            </DropdownMenuTrigger>
            <DropdownMenuContent align="end" className="w-44">
              <DropdownMenuLabel>Theme</DropdownMenuLabel>
              <DropdownMenuRadioGroup value={theme} onValueChange={(v) => setTheme(v as typeof theme)}>
                <DropdownMenuRadioItem value="light"><Sun />Light</DropdownMenuRadioItem>
                <DropdownMenuRadioItem value="dark"><Moon />Dark</DropdownMenuRadioItem>
                <DropdownMenuRadioItem value="system"><Monitor />System</DropdownMenuRadioItem>
              </DropdownMenuRadioGroup>
            </DropdownMenuContent>
          </DropdownMenu>
          <Popover open={notifOpen} onOpenChange={setNotifOpen}>
            <PopoverTrigger asChild>
              <Button variant="ghost" size="icon" aria-label="Notifications" className="relative">
                <Bell />
                {notifications.length > 0 && <span className="bg-destructive border-background absolute top-1.5 right-2 size-2 rounded-full border-2" />}
              </Button>
            </PopoverTrigger>
            <PopoverContent align="end" className="w-[340px] p-0">
              <div className="flex items-center justify-between border-b px-3 py-2.5 text-[13.5px] font-semibold">
                Notifications <span className="text-muted-foreground text-xs font-normal">{notifications.length}</span>
              </div>
              {notifications.map((n) => {
                const Icon = NOTIF_ICON[n.level]
                return (
                  <button
                    key={n.id}
                    type="button"
                    onClick={() => { setNotifOpen(false); navigate(n.path) }}
                    className="hover:bg-accent grid w-full grid-cols-[16px_1fr] gap-x-2.5 gap-y-0.5 border-b px-3 py-2.5 text-left text-[13px] last:border-0"
                  >
                    <Icon className={cn("mt-0.5 size-4", n.level === "critical" || n.level === "error" ? "text-destructive" : "text-muted-foreground")} />
                    <span className="font-medium">{n.title}</span>
                    <span className="text-muted-foreground col-start-2 text-xs">{n.body}</span>
                  </button>
                )
              })}
            </PopoverContent>
          </Popover>
          <DropdownMenu>
            <DropdownMenuTrigger asChild>
              <button type="button" className="hover:bg-accent focus-visible:ring-ring/50 flex h-9 items-center gap-2 rounded-md pr-1.5 pl-1 outline-none focus-visible:ring-[3px]">
                <span className="bg-primary/10 text-primary dark:text-chart-2 grid size-7 place-items-center rounded-full text-[11.5px] font-semibold">{initials(currentUser.name)}</span>
                <span className="hidden text-left leading-tight sm:block">
                  <span className="block text-[13px] font-medium">{currentUser.name}</span>
                  <span className="text-muted-foreground block text-[11.5px]">{ROLE_LABEL[role]}</span>
                </span>
                <ChevronDown className="text-muted-foreground size-3.5" />
              </button>
            </DropdownMenuTrigger>
            <DropdownMenuContent align="end" className="w-72">
              <DropdownMenuLabel>{currentUser.email}</DropdownMenuLabel>
              <DropdownMenuSeparator />
              {/* mock authentication: switching signs in as another demo user; roles can't be chosen here */}
              <DropdownMenuLabel className="text-muted-foreground text-xs font-normal">Mock sign-in as demo user</DropdownMenuLabel>
              <DropdownMenuRadioGroup value={currentUser.user_id} onValueChange={(id) => {
                const u = users.find((x) => x.user_id === id)
                if (u && !actions.signIn(u.email)) navigate(`/${homePage(u.role) ?? ""}`)
              }}>
                {users.filter((u) => u.status === "Active").map((u) => (
                  <DropdownMenuRadioItem key={u.user_id} value={u.user_id}>
                    <span className="truncate">{u.name}</span><span className="text-muted-foreground ml-auto pl-2 text-xs">{ROLE_LABEL[u.role]}</span>
                  </DropdownMenuRadioItem>
                ))}
              </DropdownMenuRadioGroup>
              <DropdownMenuSeparator />
              {canPage("users-settings") && <DropdownMenuItem asChild><Link to="/users-settings"><Users />Users & Settings</Link></DropdownMenuItem>}
              <DropdownMenuItem onSelect={() => { actions.signOut(); navigate("/login") }}><LogOut />Sign out</DropdownMenuItem>
            </DropdownMenuContent>
          </DropdownMenu>
        </header>
        <div role="note" className="bg-muted text-muted-foreground flex items-center gap-2 border-b px-4 py-1.5 text-xs md:px-6">
          <FlaskConical className="size-3.5 shrink-0" />
          <span><b className="text-foreground font-medium">Demonstration data.</b> Fictional company and amounts — no bank is connected. Changes stay in this browser tab and are not saved.</span>
        </div>
        <main className="mx-auto grid w-full max-w-[1440px] grid-cols-[minmax(0,1fr)] content-start gap-5 p-4 md:p-6">
          <Outlet />
        </main>
      </div>
    </div>
  )
}
