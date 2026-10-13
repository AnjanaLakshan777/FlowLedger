import { renderToString } from "react-dom/server"
import { MemoryRouter } from "react-router-dom"
import { describe, expect, it } from "vitest"
import DailySalesPage from "@/pages/daily-sales"
import HistoricalImportPage from "@/pages/historical-import"
import UsersSettingsPage from "@/pages/users-settings"
import type { ReactNode } from "react"
import { AppStateProvider } from "./app-state"
import { RequireAccess } from "./require-access"

// Demo users (mock authentication): U1 Finance Controller, U2 System Admin, U4 Cashier, U7 Accountant
const render = (userId: string, ui: ReactNode) =>
  renderToString(<AppStateProvider initialUserId={userId}><MemoryRouter>{ui}</MemoryRouter></AppStateProvider>).replaceAll("<!-- -->", "")
const guarded = (userId: string, page: Parameters<typeof RequireAccess>[0]["page"]) =>
  render(userId, <RequireAccess page={page}><p>PAGE CONTENT</p></RequireAccess>)

describe("route protection", () => {
  it("shows Access denied instead of the page for roles without access", () => {
    for (const [user, page] of [["U4", "forecast"], ["U4", "dashboard"], ["U7", "users-settings"], ["U1", "users-settings"]] as const) {
      const html = guarded(user, page)
      expect(html).toContain("Access denied")
      expect(html).not.toContain("PAGE CONTENT")
    }
  })
  it("renders the page for roles with access", () => {
    expect(guarded("U4", "daily-sales")).toContain("PAGE CONTENT")
    expect(guarded("U7", "historical-import")).toContain("PAGE CONTENT")
    expect(guarded("U1", "cheques")).toContain("PAGE CONTENT")
    expect(guarded("U2", "users-settings")).toContain("PAGE CONTENT")
  })
  it("denies everything when signed out", () => {
    expect(render("", <RequireAccess page="daily-sales"><p>PAGE CONTENT</p></RequireAccess>)).toContain("Access denied")
  })
})

describe("actions follow permissions", () => {
  it("a Cashier can enter daily sales", () => {
    const html = render("U4", <DailySalesPage />)
    expect(html).not.toContain("View only")
    expect(html).toMatch(/<button[^>]*id="ds-save"(?![^>]*disabled)/)
  })
  it("an Accountant can upload a historical file but not approve the import", () => {
    const html = render("U7", <HistoricalImportPage />)
    expect(html).toMatch(/<input type="file"(?![^>]*disabled)/)
    expect(html).toContain("Waiting for a Finance Controller to approve.")
    expect(html).toMatch(/<button[^>]*disabled[^>]*>.*?Approve import/s)
  })
  it("a System Admin manages other users but not their own account", () => {
    const html = render("U2", <UsersSettingsPage />)
    expect(html).toMatch(/Kasun Jayawardena<span[^>]*> \(you\)<\/span>/)
    expect(html).toContain("Your account")
    expect(html).toContain('aria-label="Actions for Tharindu Silva"')
    expect(html).toContain("Create user")
    expect(html).toContain("Mock authentication")
  })
})
