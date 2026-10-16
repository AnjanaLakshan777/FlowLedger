import type { Role, User } from "@/data/types"

/**
 * Central role and permission definitions.
 * FRONTEND ONLY: this decides what the UI shows. It is not security — the Spring Boot APIs must enforce the same
 * rules on every request, using the signed-in user from the server, never a role sent by the browser.
 */

export const ROLES: Role[] = ["CASHIER", "ACCOUNTANT", "FINANCE_CONTROLLER", "SYSTEM_ADMIN"]
export const ROLE_LABEL: Record<Role, string> = {
  CASHIER: "Cashier", ACCOUNTANT: "Accountant", FINANCE_CONTROLLER: "Finance Controller", SYSTEM_ADMIN: "System Admin",
}

export type PageId =
  | "dashboard" | "daily-sales" | "historical-import" | "bank-account" | "cheques" | "forecast" | "forecast-vs-actual" | "users-settings"

export type Permission =
  // pages
  | "page.dashboard" | "page.daily-sales" | "page.historical-import" | "page.bank-account" | "page.cheques"
  | "page.forecast" | "page.forecast-vs-actual" | "page.users-settings"
  // finance actions
  | "sales.enter" | "import.upload" | "import.approve" | "cheques.manage" | "bank.retrySync" | "bank.enterData"
  // system actions
  | "bank.configure" | "users.manage" | "roles.assign" | "settings.manage"
  // audit history (the Audit History page is not in the MVP UI; the access-change log is shown to System Admins)
  | "audit.financial" | "audit.security"

const FINANCE_PAGES: Permission[] = [
  "page.dashboard", "page.daily-sales", "page.historical-import", "page.bank-account", "page.cheques", "page.forecast", "page.forecast-vs-actual",
]
const ALL: Permission[] = [
  ...FINANCE_PAGES, "page.users-settings", "sales.enter", "import.upload", "import.approve", "cheques.manage", "bank.retrySync", "bank.enterData",
  "bank.configure", "users.manage", "roles.assign", "settings.manage", "audit.financial", "audit.security",
]

export const ROLE_PERMISSIONS: Record<Role, ReadonlySet<Permission>> = {
  // daily cash and card sales only
  CASHIER: new Set<Permission>(["page.daily-sales", "sales.enter"]),
  // enters sales, imports history, views finance pages; no Finance Controller-only actions
  ACCOUNTANT: new Set<Permission>([...FINANCE_PAGES, "sales.enter", "import.upload"]),
  // all finance operations and financial audit history; no user, role or system administration
  FINANCE_CONTROLLER: new Set<Permission>([...FINANCE_PAGES, "sales.enter", "import.upload", "import.approve", "cheques.manage", "bank.retrySync", "bank.enterData", "audit.financial"]),
  // everything
  SYSTEM_ADMIN: new Set<Permission>(ALL),
}

export const ROLE_DESCRIPTION: Record<Role, string> = {
  CASHIER: "Enters daily cash and card sales. No other pages.",
  ACCOUNTANT: "Enters daily sales, imports historical sales and views the finance pages.",
  FINANCE_CONTROLLER: "All finance operations: sales, imports and approvals, cheque payments and the forecast.",
  SYSTEM_ADMIN: "Everything, plus users, roles, company settings and bank configuration.",
}

/** Shown in the Roles & permissions table, in this order. */
export const PERMISSION_LABEL: [Permission, string][] = [
  ["page.dashboard", "Dashboard"], ["page.daily-sales", "Daily Sales"], ["page.historical-import", "Historical Sales Import"],
  ["page.bank-account", "Bank Account"], ["page.cheques", "Cheque Payments"], ["page.forecast", "Cash-Flow Forecast"],
  ["page.forecast-vs-actual", "Forecast vs Actual"], ["page.users-settings", "Users & Settings"],
  ["sales.enter", "Enter daily sales"], ["import.upload", "Upload historical sales file"], ["import.approve", "Approve historical import"],
  ["cheques.manage", "Add, edit and cancel cheque payments"], ["bank.retrySync", "Retry bank sync"], ["bank.enterData", "Import bank statements, enter bank data and classify transactions"], ["bank.configure", "Configure bank integration"],
  ["users.manage", "Create and deactivate users"], ["roles.assign", "Assign roles"], ["settings.manage", "Company and system settings"],
  ["audit.financial", "Financial audit history"], ["audit.security", "Security and access-change history"],
]

export const can = (role: Role | null | undefined, p: Permission) => !!role && ROLE_PERMISSIONS[role].has(p)
export const canAccessPage = (role: Role | null | undefined, page: PageId) => can(role, `page.${page}` as Permission)

/** Where a role lands after sign-in or on an unknown URL. */
const HOME_ORDER: PageId[] = ["dashboard", "daily-sales"]
export const homePage = (role: Role | null | undefined): PageId | null => HOME_ORDER.find((p) => canAccessPage(role, p)) ?? null

// ---- user administration guards (the backend must repeat these checks)

const activeAdmins = (users: User[]) => users.filter((u) => u.role === "SYSTEM_ADMIN" && u.status === "Active")

/** Returns why a role change is not allowed, or null if it is. */
export function roleChangeError(actor: User, target: User, role: Role, users: User[]): string | null {
  if (!can(actor.role, "roles.assign")) return "Only a System Admin can change roles."
  if (actor.user_id === target.user_id) return "You can't change your own role. Ask another System Admin."
  if (target.role === role) return "The user already has this role."
  if (target.role === "SYSTEM_ADMIN" && target.status === "Active" && activeAdmins(users).length <= 1) return "This is the last active System Admin."
  return null
}

/** Returns why deactivating a user is not allowed, or null if it is. */
export function deactivateError(actor: User, target: User, users: User[]): string | null {
  if (!can(actor.role, "users.manage")) return "Only a System Admin can deactivate users."
  if (actor.user_id === target.user_id) return "You can't deactivate your own account."
  if (target.status === "Deactivated") return "The user is already deactivated."
  if (target.role === "SYSTEM_ADMIN" && target.status === "Active" && activeAdmins(users).length <= 1) return "This is the last active System Admin."
  return null
}

/** Returns why a new user can't be created, or null if they can. */
export function createUserError(actor: User, input: { name: string; email: string }, users: User[]): string | null {
  if (!can(actor.role, "users.manage")) return "Only a System Admin can create users."
  if (!input.name.trim()) return "Enter the user's name."
  if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(input.email.trim())) return "Enter a valid email."
  if (users.some((u) => u.email.toLowerCase() === input.email.trim().toLowerCase())) return "A user with this email already exists."
  return null
}
