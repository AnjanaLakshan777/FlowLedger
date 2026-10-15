import { BrowserRouter, Navigate, Route, Routes } from "react-router-dom"
import { Toaster } from "@/components/ui/sonner"
import { AppLayout } from "@/components/app/app-layout"
import { AppStateProvider, useAppState, type PageId } from "@/components/app/app-state"
import { LiveAppStateProvider } from "@/components/app/live-app-state"
import { HomeRedirect, RequireAccess } from "@/components/app/require-access"
import { ThemeProvider } from "@/components/app/theme-provider"
import { lazy, Suspense, type ReactNode } from "react"

const LoginPage = lazy(() => import("@/pages/login"))
const DashboardPage = lazy(() => import("@/pages/dashboard"))
const DailySalesPage = lazy(() => import("@/pages/daily-sales"))
const HistoricalImportPage = lazy(() => import("@/pages/historical-import"))
const BankAccountPage = lazy(() => import("@/pages/bank-account"))
const ChequesPage = lazy(() => import("@/pages/cheques"))
const ForecastPage = lazy(() => import("@/pages/forecast"))
const ForecastVsActualPage = lazy(() => import("@/pages/forecast-vs-actual"))
const UsersSettingsPage = lazy(() => import("@/pages/users-settings"))

const ROUTES: { path: string; page: PageId; render: () => ReactNode }[] = [
  { path: "dashboard", page: "dashboard", render: () => <DashboardPage /> },
  { path: "daily-sales", page: "daily-sales", render: () => <DailySalesPage /> },
  { path: "historical-import", page: "historical-import", render: () => <HistoricalImportPage /> },
  { path: "bank-account", page: "bank-account", render: () => <BankAccountPage /> },
  { path: "cheques", page: "cheques", render: () => <ChequesPage /> },
  { path: "forecast", page: "forecast", render: () => <ForecastPage /> },
  { path: "forecast-vs-actual", page: "forecast-vs-actual", render: () => <ForecastVsActualPage /> },
  { path: "users-settings", page: "users-settings", render: () => <UsersSettingsPage /> },
]

/** Live backend data unless the fictional demonstration data is asked for explicitly. */
const DEMO_MODE = import.meta.env.VITE_DEMO_MODE === "true"
const StateProvider = ({ children }: { children: ReactNode }) =>
  DEMO_MODE ? <AppStateProvider>{children}</AppStateProvider> : <LiveAppStateProvider>{children}</LiveAppStateProvider>

const Loading = () => <div className="text-muted-foreground p-6 text-sm">Loading…</div>

function Protected({ children }: { children: ReactNode }) {
  const { signedIn, sessionLoading } = useAppState()
  if (sessionLoading) return <Loading />
  return signedIn ? <>{children}</> : <Navigate to="/login" replace />
}

export default function App() {
  return (
    <ThemeProvider>
      <StateProvider>
        <BrowserRouter>
          <Suspense fallback={<Loading />}>
          <Routes>
            <Route path="/login" element={<LoginPage />} />
            <Route element={<Protected><AppLayout /></Protected>}>
              <Route index element={<HomeRedirect />} />
              {ROUTES.map((r) => (
                <Route key={r.path} path={r.path} element={<RequireAccess page={r.page}>{r.render()}</RequireAccess>} />
              ))}
              <Route path="*" element={<HomeRedirect />} />
            </Route>
          </Routes>
          </Suspense>
        </BrowserRouter>
        <Toaster position="top-right" />
      </StateProvider>
    </ThemeProvider>
  )
}
