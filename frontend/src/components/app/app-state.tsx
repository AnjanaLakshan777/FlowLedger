import { createContext, useCallback, useContext, useMemo, useState, type ReactNode } from "react"
import * as seed from "@/data"
import type { AccessChange, BankTransaction, Cheque, ChequeStatus, DailySale, ImportRecord, Role, User } from "@/data/types"
import { upsertCheque } from "@/lib/cheques"
import { buildForecast, forecastStart, isUpcoming, salesReceipts, type SaleForForecast } from "@/lib/forecast"
import { addDays, sum } from "@/lib/format"
import { can, canAccessPage, createUserError, deactivateError, roleChangeError, ROLE_LABEL, type PageId, type Permission } from "@/lib/permissions"

export type { PageId }

/**
 * In-memory app state seeded from the demonstration data in src/data/*.json.
 * Nothing is persisted: every action here is where a backend API call will go (e.g. POST /cheques).
 */

/** Sales from this date onwards are checked against bank data (the demo bank history starts 28 Sep). */
export const RECEIPTS_FROM = "2026-09-25"

/**
 * MOCK AUTHENTICATION: "signing in" picks one of the demo users; there are no passwords or sessions.
 * The role always comes from that user's record, so nobody can choose their own role.
 */
function useAppStateValue(initialUserId: string | null) {
  const [userId, setUserId] = useState<string | null>(initialUserId)
  const [accessLog, setAccessLog] = useState<AccessChange[]>([])
  const [dailySales, setDailySales] = useState<DailySale[]>(seed.dailySales)
  const [cheques, setCheques] = useState<Cheque[]>(seed.cheques)
  const [transactions] = useState<BankTransaction[]>(seed.transactions) // read-only: comes from bank sync
  const [users, setUsers] = useState<User[]>(seed.users)
  const [imports, setImports] = useState<ImportRecord[]>(seed.importHistory)

  const currentUser = users.find((u) => u.user_id === userId && u.status === "Active") ?? null
  const signedIn = !!currentUser
  const role: Role | null = currentUser?.role ?? null
  /** Permission check for the signed-in user (see src/lib/permissions.ts). */
  const allow = useCallback((p: Permission) => can(role, p), [role])
  const canPage = useCallback((page: PageId) => canAccessPage(role, page), [role])

  // Actual Bank Balance = the balance reported by the bank for the one current account; expected receipts are never added
  const bankAccount = seed.bankAccount
  const actualBalance = bankAccount?.balance ?? 0
  // a scheduled cheque whose debit is confirmed in bank data is cleared (never counted twice); stored records are not rewritten
  const receipts = useMemo(() => salesReceipts(dailySales, transactions, RECEIPTS_FROM), [dailySales, transactions])
  const forecastInputs = useMemo(() => {
    // entered sales the bank hasn't shown yet, then predictions for dates with no entry
    const awaiting: SaleForForecast[] = receipts
      .filter((r) => r.state === "not_in_bank")
      .map((r) => ({ date: r.sales_date, cash_sales: r.kind === "Cash" ? r.amount : 0, card_sales: r.kind === "Card" ? r.amount : 0, actual: true }))
    const entered = new Set(dailySales.map((s) => s.sales_date))
    const predicted: SaleForForecast[] = seed.predictedSales.filter((p) => !entered.has(p.date)).map((p) => ({ ...p, actual: false }))
    return { openingBalance: actualBalance, start: forecastStart(seed.TODAY), today: seed.TODAY, sales: [...awaiting, ...predicted], cheques }
  }, [actualBalance, receipts, dailySales, cheques])
  const forecast = useMemo(() => buildForecast(forecastInputs), [forecastInputs])

  const kpis = useMemo(() => {
    const today = seed.TODAY
    const in7 = addDays(today, 7)
    // actual bank activity only (excluded duplicates are not counted)
    const todays = transactions.filter((t) => t.txn_date === today && t.match_status !== "Excluded")
    const credits = todays.filter((t) => t.amount > 0)
    const debits = todays.filter((t) => t.amount < 0)
    // MVP: scheduled cheques clearing after today are future payments; earlier ones are assumed paid
    const upcoming = cheques.filter((c) => isUpcoming(c, today))
    const next7 = upcoming.filter((c) => c.expected_clear_date <= in7)
    const low = forecast.weeks.reduce((m, w) => (w.lowest_balance < m.lowest_balance ? w : m), forecast.weeks[0])
    return {
      actualBalance,
      syncFailed: bankAccount?.sync_status === "Error",
      lastSync: bankAccount?.last_sync ?? null,
      receiptsToday: sum(credits, (t) => t.amount),
      receiptsCount: credits.length,
      paymentsToday: -sum(debits, (t) => t.amount),
      paymentsCount: debits.length,
      cheques7dAmount: sum(next7, (c) => c.amount),
      cheques7dCount: next7.length,
      upcomingAmount: sum(upcoming, (c) => c.amount),
      upcomingCount: upcoming.length,
      forecastReceipts: sum(forecast.weeks, (w) => w.receipts),
      forecastOutflow: sum(forecast.weeks, (w) => w.cheque_out),
      forecastClosing: forecast.weeks.at(-1)?.closing ?? 0,
      lowestBalance: low.lowest_balance,
      lowestDate: low.lowest_date,
    }
  }, [transactions, cheques, forecast, actualBalance])

  const actions = useMemo(() => {
    // every action re-checks permission (the UI also hides what a role can't do); the backend must check again
    const log = (action: AccessChange["action"], target: string, from: string, to: string) =>
      setAccessLog((rows) => [{ id: `AC-${rows.length + 1}`, at: seed.NOW, by: currentUser?.name ?? "", action, target, from, to }, ...rows])
    const find = (id: string) => users.find((u) => u.user_id === id)
    return {
      /** Mock sign-in by email. Returns an error message, or null when signed in. */
      signIn: (email: string) => {
        const u = users.find((x) => x.email.toLowerCase() === email.trim().toLowerCase())
        if (!u || u.status !== "Active") return "No active demo user with that email."
        setUserId(u.user_id)
        return null
      },
      signOut: () => setUserId(null),
      /** Insert or replace the entry for a sales date. */
      saveSale: (s: DailySale) => {
        if (!can(role, "sales.enter")) return false
        setDailySales((rows) => [s, ...rows.filter((r) => r.sales_date !== s.sales_date)].sort((a, b) => b.sales_date.localeCompare(a.sales_date)))
        return true
      },
      addCheque: (c: Cheque) => { if (!can(role, "cheques.manage")) return false; setCheques((rows) => upsertCheque(rows, c)); return true },
      /** Replace the cheque that had `originalNo` (its number may have been corrected). */
      updateCheque: (originalNo: string, c: Cheque) => { if (!can(role, "cheques.manage")) return false; setCheques((rows) => upsertCheque(rows, c, originalNo)); return true },
      setChequeStatus: (chequeNo: string, status: ChequeStatus) => {
        if (!can(role, "cheques.manage")) return false
        setCheques((rows) => rows.map((c) => (c.cheque_no === chequeNo ? { ...c, status } : c)))
        return true
      },
      setImportStatus: (id: string, status: ImportRecord["status"]) => {
        if (!can(role, "import.approve")) return false
        setImports((rows) => rows.map((r) => (r.import_id === id ? { ...r, status, approved_by: currentUser?.name ?? "", approved_at: seed.NOW } : r)))
        return true
      },
      /** Returns an error message, or null when the role was changed. */
      changeRole: (targetId: string, next: Role) => {
        const t = find(targetId)
        const err = !currentUser || !t ? "User not found." : roleChangeError(currentUser, t, next, users)
        if (err) return err
        setUsers((rows) => rows.map((u) => (u.user_id === targetId ? { ...u, role: next } : u)))
        log("Changed role", t!.name, ROLE_LABEL[t!.role], ROLE_LABEL[next])
        return null
      },
      /** Returns an error message, or null when the user was deactivated. */
      deactivateUser: (targetId: string) => {
        const t = find(targetId)
        const err = !currentUser || !t ? "User not found." : deactivateError(currentUser, t, users)
        if (err) return err
        setUsers((rows) => rows.map((u) => (u.user_id === targetId ? { ...u, status: "Deactivated" } : u)))
        log("Deactivated user", t!.name, t!.status, "Deactivated")
        return null
      },
      /** Returns an error message, or null when the user was created (as Invited). */
      createUser: (input: { name: string; email: string; role: Role }) => {
        const err = !currentUser ? "Not signed in." : createUserError(currentUser, input, users)
        if (err) return err
        const id = `U${users.reduce((m, u) => Math.max(m, Number(u.user_id.slice(1)) || 0), 0) + 1}`
        setUsers((rows) => [...rows, { user_id: id, name: input.name.trim(), email: input.email.trim(), role: input.role, status: "Invited", created_at: seed.TODAY }])
        log("Created user", input.name.trim(), "", ROLE_LABEL[input.role])
        return null
      },
    }
  }, [currentUser, users, role])

  return { signedIn, role, currentUser, allow, canPage, accessLog, bankAccount, dailySales, cheques, transactions, users, imports, receipts, forecastInputs, forecast, kpis, actions }
}

export type AppState = ReturnType<typeof useAppStateValue>
const Ctx = createContext<AppState | null>(null)

/** `initialUserId` is the demo user signed in at start (mock authentication); null starts signed out. */
export function AppStateProvider({ children, initialUserId = "U1" }: { children: ReactNode; initialUserId?: string | null }) {
  const value = useAppStateValue(initialUserId)
  return <Ctx.Provider value={value}>{children}</Ctx.Provider>
}

export function useAppState() {
  const v = useContext(Ctx)
  if (!v) throw new Error("useAppState must be used inside AppStateProvider")
  return v
}
