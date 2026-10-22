import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from "react"
import * as seed from "@/data"
import type { AccessChange, BankAccount, BankTransaction, Cheque, DailySale, ImportRecord, Role, User } from "@/data/types"
import type { ForecastRunSummary } from "@/lib/api/flowledger"
import { upsertCheque } from "@/lib/cheques"
import { buildForecast, forecastStart, isUpcoming, salesReceipts, type ForecastResult, type SaleForForecast } from "@/lib/forecast"
import { addDays, sum } from "@/lib/format"
import { can, canAccessPage, createUserError, deactivateError, roleChangeError, ROLE_LABEL, type PageId, type Permission } from "@/lib/permissions"

export type { PageId }

/** Data the screens load; each can be loading or have failed independently. */
export type Resource = "sales" | "bank" | "cheques" | "forecast"
export interface ResourceState { loading: boolean; error: string | null }

/** The saved forecast run on screen, with every saved run for Forecast vs Actual (live data only). */
export interface ForecastInfo {
  run: ForecastRunSummary
  runs: ForecastRunSummary[]
  /** Cheques changed after this run was made; a new run would differ. */
  outdated: boolean
}

export interface Kpis {
  /** Null when the bank has not confirmed a balance yet — never 0. */
  actualBalance: number | null
  syncFailed: boolean; lastSync: string | null
  receiptsToday: number; receiptsCount: number; paymentsToday: number; paymentsCount: number
  cheques7dAmount: number; cheques7dCount: number; upcomingAmount: number; upcomingCount: number
  /** Null when there is no forecast, or it could not estimate receipts. */
  forecastReceipts: number | null; forecastOutflow: number | null; forecastClosing: number | null
  lowestBalance: number | null; lowestDate: string | null
}

export interface AppActions {
  /** Returns an error message, or null when signed in. */
  signIn: (email: string, password?: string, remember?: boolean) => Promise<string | null>
  signOut: () => void
  switchCompany: (companyId: string) => void
  saveSale: (s: DailySale) => Promise<string | null>
  /** Corrects the amounts of a day entered by hand, with a reason; imported days are corrected by reversing the import. */
  correctSale: (s: DailySale, cash: number, card: number, reason: string) => Promise<string | null>
  addCheque: (c: Cheque) => Promise<string | null>
  updateCheque: (original: Cheque, c: Cheque) => Promise<string | null>
  cancelCheque: (c: Cheque, reason: string) => Promise<string | null>
  /** The bank returned the cheque unpaid on `bouncedOn`; it leaves the forecast. */
  bounceCheque: (c: Cheque, bouncedOn: string, reason: string) => Promise<string | null>
  /** Asks the forecast service for a forecast from the current data. */
  refreshForecast: () => Promise<string | null>
  /**
   * Brings the forecast up to date when a forecast screen opens. The service returns the saved run
   * when nothing changed, so this makes a new run only on a new day or after the data changed.
   */
  autoUpdateForecast: () => Promise<void>
  setImportStatus: (id: string, status: ImportRecord["status"]) => boolean
  changeRole: (targetId: string, next: Role) => string | null
  deactivateUser: (targetId: string) => string | null
  createUser: (input: { name: string; email: string; role: Role }) => string | null
}

/**
 * What every screen reads. "live" comes from the FlowLedger backend; "demo" is the fictional
 * data in src/data, used by the frontend tests and the explicit demo mode (VITE_DEMO_MODE=true).
 */
export interface AppState {
  mode: "live" | "demo"
  /** Today in Asia/Colombo (live) or the demo data's fixed date. */
  today: string; now: string
  signedIn: boolean; role: Role | null; roleLabel: string; currentUser: User | null
  /** True while a stored session is being checked with the identity service. */
  sessionLoading: boolean
  company: { id: string; name: string } | null
  companies: { id: string; name: string; roleLabel: string }[]
  allow: (p: Permission) => boolean
  canPage: (page: PageId) => boolean
  accessLog: AccessChange[]; users: User[]; imports: ImportRecord[]
  bankAccount: BankAccount | null; dailySales: DailySale[]; cheques: Cheque[]; transactions: BankTransaction[]
  forecast: ForecastResult; forecastInfo: ForecastInfo | null
  /** The automatic update started when a forecast screen opens (live only); error is why it failed. */
  forecastAutoUpdate: { updating: boolean; error: string | null }
  /** Demo only: inputs of the demonstration forecast, for the deferred (unrouted) Scenario Analysis page. */
  forecastInputs?: Parameters<typeof buildForecast>[0]
  kpis: Kpis
  resources: Record<Resource, ResourceState>
  reload: (r?: Resource) => void
  actions: AppActions
}

export const Ctx = createContext<AppState | null>(null)

export function useAppState() {
  const v = useContext(Ctx)
  if (!v) throw new Error("useAppState must be used inside an app state provider")
  return v
}

export const AUTO_IDLE = { updating: false, error: null }

/** Forecast screens call this so the forecast is calculated again each day and after data changes. */
export function useAutoForecast() {
  const { mode, actions } = useAppState()
  useEffect(() => { if (mode === "live") void actions.autoUpdateForecast() }, [mode, actions])
}

const READY: ResourceState = { loading: false, error: null }

/** Sales from this date onwards are checked against bank data (the demo bank history starts 28 Sep). */
export const RECEIPTS_FROM = "2026-09-25"

/**
 * DEMONSTRATION state seeded from src/data/*.json, for the frontend tests and the explicit demo
 * mode. MOCK AUTHENTICATION: "signing in" picks one of the demo users; there are no passwords or
 * sessions, and nothing is saved.
 */
function useDemoStateValue(initialUserId: string | null): AppState {
  const [userId, setUserId] = useState<string | null>(initialUserId)
  const [accessLog, setAccessLog] = useState<AccessChange[]>([])
  const [dailySales, setDailySales] = useState<DailySale[]>(seed.dailySales)
  const [cheques, setCheques] = useState<Cheque[]>(seed.cheques)
  const [transactions] = useState<BankTransaction[]>(seed.transactions)
  const [users, setUsers] = useState<User[]>(seed.users)
  const [imports, setImports] = useState<ImportRecord[]>(seed.importHistory)

  const currentUser = users.find((u) => u.user_id === userId && u.status === "Active") ?? null
  const role: Role | null = currentUser?.role ?? null
  const allow = useCallback((p: Permission) => can(role, p), [role])
  const canPage = useCallback((page: PageId) => canAccessPage(role, page), [role])

  const bankAccount = seed.bankAccount
  const actualBalance = bankAccount?.balance ?? null
  const receipts = useMemo(() => salesReceipts(dailySales, transactions, RECEIPTS_FROM), [dailySales, transactions])
  const forecastInputs = useMemo(() => {
    const awaiting: SaleForForecast[] = receipts
      .filter((r) => r.state === "not_in_bank")
      .map((r) => ({ date: r.sales_date, cash_sales: r.kind === "Cash" ? r.amount : 0, card_sales: r.kind === "Card" ? r.amount : 0, actual: true }))
    const entered = new Set(dailySales.map((s) => s.sales_date))
    const predicted: SaleForForecast[] = seed.predictedSales.filter((p) => !entered.has(p.date)).map((p) => ({ ...p, actual: false }))
    return { openingBalance: actualBalance ?? 0, start: forecastStart(seed.TODAY), today: seed.TODAY, sales: [...awaiting, ...predicted], cheques }
  }, [actualBalance, receipts, dailySales, cheques])
  const forecast = useMemo(() => buildForecast(forecastInputs), [forecastInputs])

  const kpis = useMemo<Kpis>(() => {
    const today = seed.TODAY
    const todays = transactions.filter((t) => t.txn_date === today && t.match_status !== "Excluded")
    const credits = todays.filter((t) => t.amount > 0)
    const debits = todays.filter((t) => t.amount < 0)
    const upcoming = cheques.filter((c) => isUpcoming(c, today))
    const next7 = upcoming.filter((c) => c.expected_clear_date <= addDays(today, 7))
    const low = forecast.weeks.reduce((m, w) => (w.lowest_balance < m.lowest_balance ? w : m), forecast.weeks[0])
    return {
      actualBalance, syncFailed: bankAccount?.sync_status === "Error", lastSync: bankAccount?.last_sync ?? null,
      receiptsToday: sum(credits, (t) => t.amount), receiptsCount: credits.length,
      paymentsToday: -sum(debits, (t) => t.amount), paymentsCount: debits.length,
      cheques7dAmount: sum(next7, (c) => c.amount), cheques7dCount: next7.length,
      upcomingAmount: sum(upcoming, (c) => c.amount), upcomingCount: upcoming.length,
      forecastReceipts: sum(forecast.weeks, (w) => w.receipts), forecastOutflow: sum(forecast.weeks, (w) => w.cheque_out),
      forecastClosing: forecast.weeks.at(-1)?.closing ?? null, lowestBalance: low.lowest_balance, lowestDate: low.lowest_date,
    }
  }, [transactions, cheques, forecast, actualBalance, bankAccount])

  const actions = useMemo<AppActions>(() => {
    const log = (action: AccessChange["action"], target: string, from: string, to: string) =>
      setAccessLog((rows) => [{ id: `AC-${rows.length + 1}`, at: seed.NOW, by: currentUser?.name ?? "", action, target, from, to }, ...rows])
    const find = (id: string) => users.find((u) => u.user_id === id)
    const denied = "Your role can't do this."
    return {
      signIn: async (email) => {
        const u = users.find((x) => x.email.toLowerCase() === email.trim().toLowerCase())
        if (!u || u.status !== "Active") return "No active demo user with that email."
        setUserId(u.user_id)
        return null
      },
      signOut: () => setUserId(null),
      switchCompany: () => {},
      saveSale: async (s) => {
        if (!can(role, "sales.enter")) return denied
        setDailySales((rows) => [s, ...rows.filter((r) => r.sales_date !== s.sales_date)].sort((a, b) => b.sales_date.localeCompare(a.sales_date)))
        return null
      },
      correctSale: async (s, cash, card) => {
        if (!can(role, "sales.edit")) return denied
        setDailySales((rows) => rows.map((r) => (r.sales_date === s.sales_date ? { ...r, cash_sales: cash, card_sales: card, total: cash + card, corrected_at: seed.NOW } : r)))
        return null
      },
      addCheque: async (c) => { if (!can(role, "cheques.manage")) return denied; setCheques((rows) => upsertCheque(rows, c)); return null },
      updateCheque: async (original, c) => { if (!can(role, "cheques.manage")) return denied; setCheques((rows) => upsertCheque(rows, c, original.cheque_no)); return null },
      cancelCheque: async (c) => {
        if (!can(role, "cheques.manage")) return denied
        setCheques((rows) => rows.map((x) => (x.cheque_no === c.cheque_no ? { ...x, status: "Cancelled" } : x)))
        return null
      },
      bounceCheque: async (c, bouncedOn) => {
        if (!can(role, "cheques.bounce")) return denied
        setCheques((rows) => rows.map((x) => (x.cheque_no === c.cheque_no ? { ...x, status: "Bounced", bounced_on: bouncedOn } : x)))
        return null
      },
      refreshForecast: async () => null,
      autoUpdateForecast: async () => {},
      setImportStatus: (id, status) => {
        if (!can(role, "import.approve")) return false
        setImports((rows) => rows.map((r) => (r.import_id === id ? { ...r, status, approved_by: currentUser?.name ?? "", approved_at: seed.NOW } : r)))
        return true
      },
      changeRole: (targetId, next) => {
        const t = find(targetId)
        const err = !currentUser || !t ? "User not found." : roleChangeError(currentUser, t, next, users)
        if (err) return err
        setUsers((rows) => rows.map((u) => (u.user_id === targetId ? { ...u, role: next } : u)))
        log("Changed role", t!.name, ROLE_LABEL[t!.role], ROLE_LABEL[next])
        return null
      },
      deactivateUser: (targetId) => {
        const t = find(targetId)
        const err = !currentUser || !t ? "User not found." : deactivateError(currentUser, t, users)
        if (err) return err
        setUsers((rows) => rows.map((u) => (u.user_id === targetId ? { ...u, status: "Deactivated" } : u)))
        log("Deactivated user", t!.name, t!.status, "Deactivated")
        return null
      },
      createUser: (input) => {
        const err = !currentUser ? "Not signed in." : createUserError(currentUser, input, users)
        if (err) return err
        const id = `U${users.reduce((m, u) => Math.max(m, Number(u.user_id.slice(1)) || 0), 0) + 1}`
        setUsers((rows) => [...rows, { user_id: id, name: input.name.trim(), email: input.email.trim(), role: input.role, status: "Invited", created_at: seed.TODAY }])
        log("Created user", input.name.trim(), "", ROLE_LABEL[input.role])
        return null
      },
    }
  }, [currentUser, users, role])

  const companyName = seed.settings.find((s) => s.key === "company_name")?.value ?? "Demo company"
  return {
    mode: "demo", today: seed.TODAY, now: seed.NOW,
    signedIn: !!currentUser, sessionLoading: false, role, roleLabel: role ? ROLE_LABEL[role] : "", currentUser,
    company: { id: "demo", name: companyName }, companies: [],
    allow, canPage, accessLog, users, imports,
    bankAccount, dailySales, cheques, transactions, forecast, forecastInfo: null, forecastAutoUpdate: AUTO_IDLE, forecastInputs, kpis,
    resources: { sales: READY, bank: READY, cheques: READY, forecast: READY }, reload: () => {},
    actions,
  }
}

/** Demonstration data provider. `initialUserId` is the demo user signed in at start; null starts signed out. */
export function AppStateProvider({ children, initialUserId = "U1" }: { children: ReactNode; initialUserId?: string | null }) {
  const value = useDemoStateValue(initialUserId)
  return <Ctx.Provider value={value}>{children}</Ctx.Provider>
}
