import { useCallback, useEffect, useMemo, useRef, useState, type ReactNode } from "react"
import { toast } from "sonner"
import type { BankAccount, BankTransaction, Cheque, DailySale, Role, User } from "@/data/types"
import { colomboLocal, colomboToday, forecastFromRun, toBankAccount, toCheque, toChequeRequest, toDailySale, toTransaction } from "@/lib/api/adapters"
import { ApiError, errorMessage, session } from "@/lib/api/client"
import { flowledger, type CompanyRole, type CurrentUser, type ForecastRun, type ForecastRunSummary } from "@/lib/api/flowledger"
import { addDays, sum } from "@/lib/format"
import { can, canAccessPage, type PageId, type Permission } from "@/lib/permissions"
import { AUTO_IDLE, Ctx, type AppActions, type AppState, type Kpis, type Resource, type ResourceState } from "./app-state"

/**
 * The company admin role uses the UI's administrator permission set (labelled "System Admin" in
 * the demo); a platform SYSTEM_ADMIN without a membership gets no company pages (business data).
 */
const UI_ROLE: Record<CompanyRole, Role> = {
  COMPANY_ADMIN: "SYSTEM_ADMIN", FINANCE_CONTROLLER: "FINANCE_CONTROLLER", ACCOUNTANT: "ACCOUNTANT", CASHIER: "CASHIER",
}
const ROLE_NAME: Record<CompanyRole, string> = {
  COMPANY_ADMIN: "Company Admin", FINANCE_CONTROLLER: "Finance Controller", ACCOUNTANT: "Accountant", CASHIER: "Cashier",
}
const COMPANY_KEY = "flowledger.companyId"
const IDLE: ResourceState = { loading: false, error: null }
const LOADING: ResourceState = { loading: true, error: null }
const EMPTY_FORECAST = { days: [], weeks: [], shortages: [] }
// Until a resource's first answer arrives it counts as loading, never as empty.
const NOT_LOADED: Record<Resource, ResourceState> = { sales: LOADING, bank: LOADING, cheques: LOADING, forecast: LOADING }

interface BankData { account: BankAccount | null; transactions: BankTransaction[] }
interface ForecastData { run: ForecastRun | null; runs: ForecastRunSummary[] }

const storedCompany = () => { try { return localStorage.getItem(COMPANY_KEY) } catch { return null } }

/** State backed by the FlowLedger services. Nothing falls back to demonstration data. */
function useLiveStateValue(): AppState {
  const [me, setMe] = useState<CurrentUser | null>(null)
  const [sessionLoading, setSessionLoading] = useState(() => !!session.token())
  const [companyId, setCompanyId] = useState<string | null>(storedCompany)
  const [resources, setResources] = useState<Record<Resource, ResourceState>>(NOT_LOADED)
  const [sales, setSales] = useState<DailySale[]>([])
  const [bank, setBank] = useState<BankData>({ account: null, transactions: [] })
  const [cheques, setCheques] = useState<Cheque[]>([])
  const [forecastData, setForecastData] = useState<ForecastData>({ run: null, runs: [] })
  const [outdated, setOutdated] = useState(false)
  const [autoUpdate, setAutoUpdate] = useState<AppState["forecastAutoUpdate"]>(AUTO_IDLE)
  const autoBusy = useRef(false)
  const shownRunId = useRef<string | null>(null)
  const generation = useRef(0) // answers that arrive after sign-out or a company switch are dropped

  const membership = me?.memberships.find((m) => m.companyId === companyId) ?? me?.memberships[0] ?? null
  const role: Role | null = membership ? UI_ROLE[membership.companyRole] : null
  const company = membership ? { id: membership.companyId, name: membership.companyName } : null
  const allow = useCallback((p: Permission) => can(role, p), [role])
  const canPage = useCallback((page: PageId) => canAccessPage(role, page), [role])

  const reset = useCallback(() => {
    generation.current++
    setMe(null); setSales([]); setBank({ account: null, transactions: [] }); setCheques([]); setForecastData({ run: null, runs: [] })
    setResources(NOT_LOADED); setOutdated(false); setAutoUpdate(AUTO_IDLE)
  }, [])

  const signOut = useCallback(() => { session.clear(); reset(); setSessionLoading(false) }, [reset])

  // An expired or rejected token ends the session everywhere.
  useEffect(() => {
    session.onUnauthorized(() => { toast.info("Your session has ended. Please sign in again."); signOut() })
    return () => session.onUnauthorized(null)
  }, [signOut])

  const loadMe = useCallback(async () => {
    setSessionLoading(true)
    try {
      setMe(await flowledger.me())
      return null
    } catch (e) {
      session.clear()
      return errorMessage(e)
    } finally {
      setSessionLoading(false)
    }
  }, [])
  useEffect(() => { if (session.token()) void loadMe() }, [loadMe])

  const load = useCallback(async (resource: Resource, cid: string) => {
    const gen = generation.current
    setResources((r) => ({ ...r, [resource]: LOADING }))
    try {
      if (resource === "sales") {
        const rows = await flowledger.sales(cid)
        if (gen === generation.current) setSales(rows.map((s) => toDailySale(s, me?.userId ?? null)).sort((a, b) => b.sales_date.localeCompare(a.sales_date)))
      } else if (resource === "bank") {
        const accounts = await flowledger.bankAccounts(cid)
        const current = accounts.find((a) => a.accountType === "CURRENT" && a.status === "ACTIVE")
        if (!current) {
          if (gen === generation.current) setBank({ account: null, transactions: [] })
        } else {
          const [balance, imports, transactions] = await Promise.all([
            // 404: no confirmed balance yet — shown as missing, never as 0
            flowledger.latestBalance(cid, current.id).catch((e) => { if (e instanceof ApiError && e.status === 404) return null; throw e }),
            flowledger.statementImports(cid),
            flowledger.transactions(cid, current.id),
          ])
          if (gen === generation.current) setBank({ account: toBankAccount(current, balance, imports), transactions: transactions.map(toTransaction) })
        }
      } else if (resource === "cheques") {
        const rows = await flowledger.cheques(cid)
        if (gen === generation.current) setCheques(rows.map(toCheque))
      } else {
        const runs = await flowledger.forecastRuns(cid)
        const run = runs[0] ? await flowledger.forecastRun(cid, runs[0].id) : null
        if (gen === generation.current) {
          shownRunId.current = run?.run.id ?? null
          setForecastData({ run, runs })
        }
      }
      if (gen === generation.current) setResources((r) => ({ ...r, [resource]: IDLE }))
    } catch (e) {
      if (gen === generation.current) setResources((r) => ({ ...r, [resource]: { loading: false, error: errorMessage(e) } }))
    }
  }, [me?.userId])

  // Load what the role can see; each service checks the role again on every request.
  const needed = useCallback((): Resource[] => [
    ...(canPage("daily-sales") ? ["sales" as const] : []),
    ...(canPage("bank-account") ? ["bank" as const] : []),
    ...(canPage("cheques") ? ["cheques" as const] : []),
    ...(canPage("forecast") ? ["forecast" as const] : []),
  ], [canPage])

  const cid = company?.id ?? null
  useEffect(() => {
    if (!cid) return
    const wanted = needed()
    // a resource the role cannot see is never loaded, so it must not stay "loading"
    setResources((r) => ({ ...r, ...Object.fromEntries((Object.keys(NOT_LOADED) as Resource[]).filter((x) => !wanted.includes(x)).map((x) => [x, IDLE])) }))
    for (const r of wanted) void load(r, cid)
  }, [cid, needed, load])

  const reload = useCallback((r?: Resource) => {
    if (!cid) return
    for (const x of r ? [r] : needed()) void load(x, cid)
  }, [cid, needed, load])

  const today = colomboToday()
  const forecast = useMemo(() => (forecastData.run ? forecastFromRun(forecastData.run, cheques) : EMPTY_FORECAST), [forecastData.run, cheques])

  const kpis = useMemo<Kpis>(() => {
    const todays = bank.transactions.filter((t) => t.txn_date === today)
    const credits = todays.filter((t) => t.amount > 0)
    const debits = todays.filter((t) => t.amount < 0)
    const upcoming = cheques.filter((c) => c.status === "Scheduled" && c.expected_clear_date > today)
    const next7 = upcoming.filter((c) => c.expected_clear_date <= addDays(today, 7))
    const totals = forecastData.run?.totals
    return {
      actualBalance: bank.account?.balance ?? null,
      syncFailed: bank.account?.sync_status === "Error", lastSync: bank.account?.last_sync || null,
      receiptsToday: sum(credits, (t) => t.amount), receiptsCount: credits.length,
      paymentsToday: -sum(debits, (t) => t.amount), paymentsCount: debits.length,
      cheques7dAmount: sum(next7, (c) => c.amount), cheques7dCount: next7.length,
      upcomingAmount: sum(upcoming, (c) => c.amount), upcomingCount: upcoming.length,
      forecastReceipts: totals?.expectedSalesInflows ?? null, forecastOutflow: totals?.expectedChequeOutflows ?? null,
      forecastClosing: totals?.closingBalance ?? null, lowestBalance: totals?.lowestClosingBalance ?? null, lowestDate: totals?.lowestBalanceDate ?? null,
    }
  }, [bank, cheques, forecastData.run, today])

  const actions = useMemo<AppActions>(() => {
    const run = async (work: () => Promise<unknown>, after: () => void) => {
      try {
        await work()
        after()
        return null
      } catch (e) {
        return errorMessage(e)
      }
    }
    const chequesChanged = () => { reload("cheques"); setOutdated(true) }
    const unsupported = () => "Not available yet: this needs a backend API."
    return {
      signIn: async (email, password = "", remember = false) => {
        try {
          const { accessToken } = await flowledger.login(email.trim(), password)
          session.set(accessToken, remember)
        } catch (e) {
          return e instanceof ApiError && e.status === 401 ? "Email or password is incorrect, or the account is not active yet." : errorMessage(e)
        }
        return loadMe()
      },
      signOut,
      switchCompany: (id) => {
        try { localStorage.setItem(COMPANY_KEY, id) } catch { /* not remembered */ }
        generation.current++
        setSales([]); setBank({ account: null, transactions: [] }); setCheques([]); setForecastData({ run: null, runs: [] }); setOutdated(false)
        setResources(NOT_LOADED); setAutoUpdate(AUTO_IDLE); shownRunId.current = null
        setCompanyId(id)
      },
      saveSale: async (s) => {
        if (!cid) return "No company selected."
        try {
          await flowledger.recordSale(cid, s.sales_date, s.cash_sales, s.card_sales)
          reload("sales")
          return null
        } catch (e) {
          return e instanceof ApiError && e.status === 409 ? "Sales for this date are already recorded. Correct the recorded day instead." : errorMessage(e)
        }
      },
      correctSale: (s, cash, card, reason) => run(() => flowledger.correctSale(cid!, s.id!, cash, card, reason, s.version!), () => reload("sales")),
      addCheque: (c) => run(() => flowledger.addCheque(cid!, toChequeRequest(c)), chequesChanged),
      updateCheque: (original, c) =>
        run(() => flowledger.updateCheque(cid!, original.id!, toChequeRequest({ ...c, id: original.id, version: original.version })), chequesChanged),
      cancelCheque: (c, reason) => run(() => flowledger.cancelCheque(cid!, c.id!, reason, c.version!), chequesChanged),
      bounceCheque: (c, bouncedOn, reason) => run(() => flowledger.bounceCheque(cid!, c.id!, bouncedOn, reason, c.version!), chequesChanged),
      refreshForecast: () => run(() => flowledger.createForecast(cid!), () => { setOutdated(false); setAutoUpdate(AUTO_IDLE); reload("forecast") }),
      autoUpdateForecast: async () => {
        if (!cid || !canPage("forecast") || autoBusy.current) return
        autoBusy.current = true
        const gen = generation.current
        setAutoUpdate({ updating: true, error: null })
        try {
          const made = await flowledger.createForecast(cid)
          if (gen !== generation.current) return
          setAutoUpdate(AUTO_IDLE)
          // The same run comes back when nothing changed since it was made.
          if (made.run.id !== shownRunId.current) { setOutdated(false); reload("forecast") }
        } catch (e) {
          if (gen === generation.current) setAutoUpdate({ updating: false, error: errorMessage(e) })
        } finally {
          autoBusy.current = false
        }
      },
      setImportStatus: () => false,
      changeRole: unsupported,
      deactivateUser: unsupported,
      createUser: unsupported,
    }
  }, [cid, canPage, reload, loadMe, signOut])

  const currentUser: User | null = me
    ? { user_id: me.userId, name: me.fullName, email: me.email, role: role ?? "CASHIER", status: "Active", created_at: "" }
    : null
  const runSummary = forecastData.run?.run ?? null

  return {
    mode: "live", today, now: colomboLocal(new Date()),
    signedIn: !!me, sessionLoading, role, roleLabel: membership ? ROLE_NAME[membership.companyRole] : "", currentUser,
    company, companies: (me?.memberships ?? []).map((m) => ({ id: m.companyId, name: m.companyName, roleLabel: ROLE_NAME[m.companyRole] })),
    allow, canPage, accessLog: [], users: [], imports: [],
    bankAccount: bank.account, dailySales: sales, cheques, transactions: bank.transactions,
    forecast, forecastInfo: runSummary ? { run: runSummary, runs: forecastData.runs, outdated } : null, forecastAutoUpdate: autoUpdate,
    kpis, resources, reload, actions,
  }
}

export function LiveAppStateProvider({ children }: { children: ReactNode }) {
  const value = useLiveStateValue()
  return <Ctx.Provider value={value}>{children}</Ctx.Provider>
}
