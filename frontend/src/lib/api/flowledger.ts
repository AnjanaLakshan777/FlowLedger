import { api } from "./client"

/** Response shapes of the FlowLedger services, as they send them. Money comes as JSON numbers. */
export type CompanyRole = "COMPANY_ADMIN" | "FINANCE_CONTROLLER" | "ACCOUNTANT" | "CASHIER"

export interface CurrentUser {
  userId: string; email: string; fullName: string; platformRole: "USER" | "SYSTEM_ADMIN"
  memberships: { membershipId: string; companyId: string; companyName: string; companyRole: CompanyRole }[]
}

export interface ApiDailySale {
  id: string; companyId: string; saleDate: string; cashSalesAmount: number; cardSalesAmount: number; totalSalesAmount: number
  entrySource: string; recordedBy: string; recordedAt: string
}

export interface ApiBankAccount {
  id: string; bankName: string; accountName: string; accountType: string; accountNumberLast4: string | null
  currency: string; integrationMode: string; status: string
}

export interface ApiBalance { bankAccountId: string; balanceAmount: number; currency: string; balanceAsOf: string; sourceType: string }

export interface ApiStatementImport {
  id: string; bankAccountId: string; filename: string; status: "IMPORTED" | "FAILED" | string
  periodStart: string | null; periodEnd: string | null; importedAt: string; errorSummary: string | null
}

export type TransactionCategory = "CASH_SALES_DEPOSIT" | "CARD_SALES_SETTLEMENT" | "CHEQUE_PAYMENT" | "OTHER" | "UNCLASSIFIED"

export interface ApiTransaction {
  id: string; bankAccountId: string; transactionDate: string; direction: "CREDIT" | "DEBIT"; amount: number
  currency: string; description: string | null; runningBalance: number | null; chequeNumber: string | null; category: TransactionCategory
}

export type ApiChequeCategory = "SUPPLIER_PAYMENT" | "SALARY" | "TAX" | "OTHER_EXPENSES"

export interface ApiCheque {
  id: string; bankAccountId: string; chequeNumber: string; payeeName: string; category: ApiChequeCategory; amount: number
  issueDate: string; expectedClearingDate: string; status: "ISSUED" | "CANCELLED"; notes: string | null; version: number
}

export interface ChequeRequest {
  chequeNumber: string; payeeName: string; category: ApiChequeCategory; amount: number; issueDate: string
  expectedClearingDate: string; notes: string | null; version?: number
}

export interface ForecastWarning { code: "BALANCE_STALE" | "INSUFFICIENT_HISTORY" | "INCOMPLETE_CLASSIFICATION" | "ACTUALS_INCOMPLETE" | string; message: string }

export interface ForecastRunSummary {
  id: string; bankAccountId: string; runStatus: string; asOfAt: string; horizonStartDate: string; horizonEndDate: string
  openingBalance: number; openingBalanceAsOf: string; currency: string; balanceStale: boolean
  inflowEstimateStatus: "ESTIMATED" | "INSUFFICIENT_HISTORY"
  dataQuality: { historyFrom: string; historyTo: string; balanceAgeDays: number; balanceStaleAfterDays: number; unclassifiedCreditCount: number }
  warnings: ForecastWarning[]; createdAt: string
}

export interface ForecastRunDay {
  date: string; expectedCashSalesDeposits: number | null; expectedCardSalesSettlements: number | null; expectedSalesInflows: number | null
  expectedChequeOutflows: number; expectedChequeCount: number; openingBalance: number; closingBalance: number; cashShortage: boolean
}

export interface ForecastRun {
  run: ForecastRunSummary
  totals: {
    expectedSalesInflows: number | null; expectedChequeOutflows: number; closingBalance: number; lowestClosingBalance: number
    lowestBalanceDate: string; firstShortageDate: string | null; shortageDays: number
  }
  days: ForecastRunDay[]
  cheques: { chequeId: string; chequeNumber: string; amount: number; expectedClearingDate: string }[]
}

export interface ForecastVsActualDay {
  date: string; actualsAvailable: boolean
  forecastSalesInflows: number | null; actualSalesInflows: number | null
  forecastChequeOutflows: number; actualChequeOutflows: number | null
  forecastClosingBalance: number; actualClosingBalance: number | null; unclassifiedTransactions: number
}

export interface ForecastVsActual {
  forecastRunId: string; horizonStartDate: string; horizonEndDate: string; inflowEstimateStatus: string; comparedThrough: string | null
  days: ForecastVsActualDay[]; warnings: ForecastWarning[]
  totals: { daysCompared: number; unclassifiedTransactionCount: number; unclassifiedTransactionAmount: number }
}

const identity = "/api/v1/identity"
const cash = (c: string) => `/api/v1/cash-management/companies/${c}`
const bank = (c: string) => `/api/v1/bank-integration/companies/${c}`
const payment = (c: string) => `/api/v1/payment/companies/${c}`
const forecast = (c: string) => `/api/v1/forecast/companies/${c}`

export const flowledger = {
  login: (email: string, password: string) =>
    api<{ accessToken: string; expiresIn: number }>(`${identity}/auth/login`, { method: "POST", json: { email, password }, anonymous: true }),
  me: () => api<CurrentUser>(`${identity}/me`),

  sales: (c: string) => api<ApiDailySale[]>(`${cash(c)}/sales`),
  recordSale: (c: string, saleDate: string, cashSalesAmount: number, cardSalesAmount: number) =>
    api<ApiDailySale>(`${cash(c)}/sales`, { method: "POST", json: { saleDate, cashSalesAmount, cardSalesAmount } }),

  bankAccounts: (c: string) => api<ApiBankAccount[]>(`${bank(c)}/bank-accounts`),
  latestBalance: (c: string, accountId: string) => api<ApiBalance>(`${bank(c)}/bank-accounts/${accountId}/balance-snapshots/latest`),
  statementImports: (c: string) => api<ApiStatementImport[]>(`${bank(c)}/statement-imports`),
  transactions: (c: string, accountId: string) => api<ApiTransaction[]>(`${bank(c)}/bank-transactions?bankAccountId=${accountId}`),

  cheques: (c: string) => api<ApiCheque[]>(`${payment(c)}/cheques`),
  addCheque: (c: string, body: ChequeRequest) => api<ApiCheque>(`${payment(c)}/cheques`, { method: "POST", json: body }),
  updateCheque: (c: string, id: string, body: ChequeRequest) => api<ApiCheque>(`${payment(c)}/cheques/${id}`, { method: "PUT", json: body }),
  cancelCheque: (c: string, id: string, reason: string, version: number) =>
    api<ApiCheque>(`${payment(c)}/cheques/${id}/cancel`, { method: "POST", json: { reason, version } }),

  forecastRuns: (c: string) => api<ForecastRunSummary[]>(`${forecast(c)}/forecast-runs`),
  forecastRun: (c: string, runId: string) => api<ForecastRun>(`${forecast(c)}/forecast-runs/${runId}`),
  createForecast: (c: string) => api<ForecastRun>(`${forecast(c)}/forecast-runs`, { method: "POST" }),
  forecastVsActual: (c: string, runId: string) => api<ForecastVsActual>(`${forecast(c)}/forecast-runs/${runId}/forecast-vs-actual`),
}
