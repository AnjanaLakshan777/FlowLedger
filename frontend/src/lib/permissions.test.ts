import { describe, expect, it } from "vitest"
import type { Role, User } from "@/data/types"
import { visibleNav } from "@/components/app/nav"
import {
  can, canAccessPage, createUserError, deactivateError, homePage, PERMISSION_LABEL, roleChangeError, type PageId, type Permission,
} from "./permissions"

const PAGES: PageId[] = ["dashboard", "daily-sales", "historical-import", "bank-account", "cheques", "forecast", "forecast-vs-actual", "users-settings"]
const pagesFor = (r: Role) => PAGES.filter((p) => canAccessPage(r, p))
const FINANCE: PageId[] = ["dashboard", "daily-sales", "historical-import", "bank-account", "cheques", "forecast", "forecast-vs-actual"]

describe("role permissions", () => {
  it("Cashier: Daily Sales only, can enter sales, cannot import", () => {
    expect(pagesFor("CASHIER")).toEqual(["daily-sales"])
    expect(can("CASHIER", "sales.enter")).toBe(true)
    expect(can("CASHIER", "import.upload")).toBe(false)
    expect(homePage("CASHIER")).toBe("daily-sales")
  })

  it("Accountant: finance pages, enters sales, imports history and monthly bank data, records bounced cheques", () => {
    expect(pagesFor("ACCOUNTANT")).toEqual(FINANCE)
    for (const p of ["sales.enter", "import.upload", "bank.importMonthly", "cheques.bounce"] as Permission[]) expect(can("ACCOUNTANT", p)).toBe(true)
    for (const p of ["import.approve", "cheques.manage", "bank.retrySync", "bank.enterData", "users.manage", "roles.assign", "settings.manage"] as Permission[]) {
      expect(can("ACCOUNTANT", p)).toBe(false)
    }
  })

  it("Finance Controller: all finance operations and financial audit, no user or role administration", () => {
    expect(pagesFor("FINANCE_CONTROLLER")).toEqual(FINANCE)
    for (const p of ["sales.enter", "import.upload", "import.approve", "cheques.manage", "cheques.bounce", "bank.retrySync", "bank.enterData", "bank.importMonthly", "audit.financial"] as Permission[]) {
      expect(can("FINANCE_CONTROLLER", p)).toBe(true)
    }
    for (const p of ["users.manage", "roles.assign", "settings.manage", "bank.configure", "audit.security"] as Permission[]) {
      expect(can("FINANCE_CONTROLLER", p)).toBe(false)
    }
  })

  it("System Admin: every page and every permission", () => {
    expect(pagesFor("SYSTEM_ADMIN")).toEqual(PAGES)
    expect(PERMISSION_LABEL.every(([p]) => can("SYSTEM_ADMIN", p))).toBe(true)
  })

  it("nobody signed in has no access", () => {
    expect(PAGES.some((p) => canAccessPage(null, p))).toBe(false)
    expect(homePage(null)).toBeNull()
  })

  it("navigation hides pages a role can't open", () => {
    const labels = (r: Role) => visibleNav(r).flatMap((g) => g.items.map((i) => i.label))
    expect(labels("CASHIER")).toEqual(["Daily Sales"])
    expect(labels("ACCOUNTANT")).not.toContain("Users & Settings")
    expect(labels("FINANCE_CONTROLLER")).not.toContain("Users & Settings")
    expect(labels("SYSTEM_ADMIN")).toContain("Users & Settings")
    expect(visibleNav("CASHIER").map((g) => g.group)).toEqual(["Sales"]) // empty groups are hidden too
  })
})

describe("user administration safeguards", () => {
  const user = (id: string, role: Role, status: User["status"] = "Active"): User => ({ user_id: id, name: id, email: `${id}@x.lk`, role, status, created_at: "2026-01-01" })
  const admin = user("A1", "SYSTEM_ADMIN"), fc = user("F1", "FINANCE_CONTROLLER"), cashier = user("C1", "CASHIER")
  const users = [admin, fc, cashier]

  it("lets a System Admin change another user's role", () => {
    expect(roleChangeError(admin, cashier, "ACCOUNTANT", users)).toBeNull()
  })
  it("stops non-admins from changing roles, including their own (no self-granted permissions)", () => {
    expect(roleChangeError(fc, cashier, "ACCOUNTANT", users)).toMatch(/Only a System Admin/)
    expect(roleChangeError(fc, fc, "SYSTEM_ADMIN", users)).toMatch(/Only a System Admin/)
  })
  it("stops anyone changing their own role", () => {
    expect(roleChangeError(admin, admin, "FINANCE_CONTROLLER", users)).toMatch(/own role/)
  })
  it("protects the last active System Admin from demotion and deactivation", () => {
    const admin2 = user("A2", "SYSTEM_ADMIN")
    const list = [admin, admin2, fc]
    expect(roleChangeError(admin, admin2, "CASHIER", list)).toBeNull() // two active admins: allowed
    const withOneActive = [admin, { ...admin2, status: "Deactivated" as const }, fc]
    const other = user("A3", "SYSTEM_ADMIN")
    // A3 (inactive-admin scenario): the only active admin can't be demoted or deactivated by another admin account
    expect(roleChangeError(other, admin, "CASHIER", withOneActive)).toMatch(/last active System Admin/)
    expect(deactivateError(other, admin, withOneActive)).toMatch(/last active System Admin/)
  })
  it("stops users deactivating themselves, and non-admins deactivating anyone", () => {
    expect(deactivateError(admin, admin, users)).toMatch(/your own account/)
    expect(deactivateError(fc, cashier, users)).toMatch(/Only a System Admin/)
    expect(deactivateError(admin, cashier, users)).toBeNull()
  })
  it("validates new users and rejects duplicate emails", () => {
    expect(createUserError(admin, { name: "New", email: "new@x.lk" }, users)).toBeNull()
    expect(createUserError(admin, { name: "Dup", email: "C1@X.LK" }, users)).toMatch(/already exists/)
    expect(createUserError(admin, { name: "Bad", email: "not-an-email" }, users)).toMatch(/valid email/)
    expect(createUserError(fc, { name: "New", email: "new@x.lk" }, users)).toMatch(/Only a System Admin/)
  })
})
