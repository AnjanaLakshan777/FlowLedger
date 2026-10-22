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
  /** Set once the day has been corrected. */
  updatedBy: string | null; updatedAt: string | null; version: number
}

export interface ApiBankAccount {
  id: string; bankName: string; branchName: string | null; accountName: string; accountType: string; accountNumberLast4: string | null
  currency: string; integrationMode: string; status: string
}

export interface ApiBalance { bankAccountId: string; balanceAmount: number; currency: string; balanceAsOf: string; sourceType: string }

export interface StatementError { line: number | null; field: string | null; message: string }

export interface ApiStatementImport {
  id: string; bankAccountId: string; filename: string; status: "IMPORTED" | "FAILED" | "REMOVED" | string
  /** FLOWLEDGER_MONTHLY_CSV for the monthly template, TRANSACTION_DETAILS_CSV for the bank's own statement. */
  fileFormat: string
  periodStart: string | null; periodEnd: string | null; importedAt: string; errorSummary: string | null
  rowCount: number; importedRowCount: number; duplicateRowCount: number; skippedRowCount: number
  validationErrors: StatementError[]; earlierImportsOfSameFile: string[]
  /** Set when a user removed the import; its transactions and balance were deleted. */
  removedAt: string | null; removedBy: string | null
}

export interface ManualTransactionRequest {
  transactionDate: string; direction: "CREDIT" | "DEBIT"; amount: number; description: string
  chequeNumber: string | null; category: TransactionCategory | null
  /** Required for OTHER: what the transaction was. */
  otherDescription: string | null
}

export type TransactionCategory = "CASH_SALES_DEPOSIT" | "CARD_SALES_SETTLEMENT" | "CHEQUE_PAYMENT" | "OTHER" | "UNCLASSIFIED"

export interface ApiTransaction {
  id: string; bankAccountId: string; transactionDate: string; direction: "CREDIT" | "DEBIT"; amount: number
  currency: string; description: string | null; runningBalance: number | null; chequeNumber: string | null; category: TransactionCategory
  /** What an OTHER transaction was; null for every other category. */
  otherDescription: string | null
  classificationMethod: "BANK_METADATA" | "RULE" | "MANUAL" | "UNKNOWN"; entrySource: "STATEMENT_IMPORT" | "MANUAL"; sourceImportBatchId: string | null
}

export type ApiChequeCategory = "SUPPLIER_PAYMENT" | "SALARY" | "TAX" | "OTHER_EXPENSES"

export interface ApiCheque {
  id: string; bankAccountId: string; chequeNumber: string; payeeName: string; category: ApiChequeCategory
  /** What an OTHER_EXPENSES cheque was; null for every other category. */
  otherDescription: string | null; amount: number
  issueDate: string; expectedClearingDate: string; status: "ISSUED" | "CANCELLED" | "BOUNCED"; notes: string | null; version: number
  /** Set only for a BOUNCED cheque: the day the bank returned it. */
  bouncedOn: string | null
}

export interface ChequeRequest {
  chequeNumber: string; payeeName: string; category: ApiChequeCategory
  /** Required for OTHER_EXPENSES: what the payment was. */
  otherDescription: string | null; amount: number; issueDate: string
  expectedClearingDate: string; notes: string | null; version?: number
}

export interface ForecastWarning {
  code: "BALANCE_STALE" | "INSUFFICIENT_HISTORY" | "INCOMPLETE_CLASSIFICATION" | "ACTUALS_INCOMPLETE" | "PATTERN_FROM_SALES_HISTORY" | "GROWTH_RATIO_OUT_OF_RANGE" | string
  message: string
}

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
  forecastRunId: string; horizonStartDate: string; horizonEndDate: string; inflowEstimateStatus: string
  /** The last day that can have actuals: the end of last month (bank transactions are imported monthly). */
  actualsThrough: string; comparedThrough: string | null
  days: ForecastVsActualDay[]; warnings: ForecastWarning[]
  totals: { daysCompared: number; unclassifiedTransactionCount: number; unclassifiedTransactionAmount: number }
}

/** Historical sales import (cash-management-service). Amounts in LKR; totals cover valid rows only. */
export type SalesImportStatus = "PENDING_APPROVAL" | "APPROVED" | "REJECTED" | "INVALID" | "REVERSED"

export interface ApiSalesImport {
  id: string; fileName: string; status: SalesImportStatus; fileError: string | null
  periodStart: string | null; periodEnd: string | null
  rowCount: number; validRowCount: number; invalidRowCount: number; duplicateRowCount: number
  cashSalesTotal: number; cardSalesTotal: number
  uploadedBy: string; uploadedAt: string
  approvedBy: string | null; approvedAt: string | null; approvalNote: string | null
  importedRowCount: number | null; excludedRowCount: number | null
  rejectedBy: string | null; rejectedAt: string | null; rejectionReason: string | null
  /** Set when an approved import was reversed: its daily sales were deleted. */
  reversedBy: string | null; reversedAt: string | null; reversalReason: string | null
  version: number
}

export interface ApiSalesImportRow {
  rowNumber: number; saleDate: string | null; cashSalesAmount: number | null; cardSalesAmount: number | null
  status: "VALID" | "INVALID" | "DUPLICATE"; duplicateDate: boolean
  errors: { field: string; code: string; message: string }[]
  /** The cells as written, for rows that are not valid. */
  raw: Record<string, string> | null
}

export interface ApiSalesImportDetail { batch: ApiSalesImport; rows: ApiSalesImportRow[] }

export interface ApiSalesImportOverview { canUpload: boolean; canApprove: boolean; ownImportsOnly: boolean; imports: ApiSalesImport[] }

/** Users & Settings (identity-service). */
export type MembershipStatus = "ACTIVE" | "INVITED" | "SUSPENDED"

export interface ApiMembership {
  membershipId: string; companyId: string; userId: string; email: string; fullName: string
  companyRole: CompanyRole; status: MembershipStatus; accountStatus: "ACTIVE" | "LOCKED" | "DISABLED" | "PENDING"; createdAt: string
}

export interface CreateMembershipRequest { email: string; fullName: string; companyRole: CompanyRole; temporaryPassword: string | null }

export interface ApiCompany {
  id: string; name: string; registrationNumber: string | null; defaultCurrency: string; timezone: string; status: string; createdAt: string
}

export interface ApiAccessChange {
  id: string; occurredAt: string
  action: "MEMBERSHIP_CREATED" | "MEMBERSHIP_ROLE_CHANGED" | "MEMBERSHIP_STATUS_CHANGED" | "COMPANY_UPDATED" | string
  actorUserId: string | null; actorName: string | null; targetUserId: string | null; targetName: string | null
  /** Company edits only: "name" or "registrationNumber". */
  field: string | null; from: string | null; to: string | null
}

export type IntegrationMode = "STATEMENT_IMPORT" | "MANUAL"

export interface CreateBankAccountRequest {
  bankName: string; branchName: string; accountName: string; accountType: "CURRENT"; accountNumberLast4: string | null; integrationMode: IntegrationMode
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

  memberships: (c: string) => api<ApiMembership[]>(`${identity}/companies/${c}/memberships`),
  createMembership: (c: string, body: CreateMembershipRequest) =>
    api<{ membership: ApiMembership; accountCreated: boolean }>(`${identity}/companies/${c}/memberships`, { method: "POST", json: body }),
  updateMembership: (c: string, id: string, body: { companyRole?: CompanyRole; status?: "ACTIVE" | "SUSPENDED" }) =>
    api<ApiMembership>(`${identity}/companies/${c}/memberships/${id}`, { method: "PATCH", json: body }),
  company: (c: string) => api<ApiCompany>(`${identity}/companies/${c}`),
  updateCompany: (c: string, body: { name: string; registrationNumber: string | null }) =>
    api<ApiCompany>(`${identity}/companies/${c}`, { method: "PATCH", json: body }),
  accessChanges: (c: string) => api<ApiAccessChange[]>(`${identity}/companies/${c}/access-changes`),

  sales: (c: string) => api<ApiDailySale[]>(`${cash(c)}/sales`),
  recordSale: (c: string, saleDate: string, cashSalesAmount: number, cardSalesAmount: number) =>
    api<ApiDailySale>(`${cash(c)}/sales`, { method: "POST", json: { saleDate, cashSalesAmount, cardSalesAmount } }),
  /** Corrects a day entered by hand (amounts only), with a reason for the audit history. */
  correctSale: (c: string, id: string, cashSalesAmount: number, cardSalesAmount: number, reason: string, version: number) =>
    api<ApiDailySale>(`${cash(c)}/sales/${id}`, { method: "PUT", json: { cashSalesAmount, cardSalesAmount, reason, version } }),

  salesImports: (c: string) => api<ApiSalesImportOverview>(`${cash(c)}/sales-imports`),
  salesImport: (c: string, id: string) => api<ApiSalesImportDetail>(`${cash(c)}/sales-imports/${id}`),
  uploadSalesImport: (c: string, file: File) => {
    const form = new FormData()
    form.append("file", file)
    return api<ApiSalesImportDetail>(`${cash(c)}/sales-imports`, { method: "POST", form })
  },
  approveSalesImport: (c: string, id: string, version: number, excludeInvalidRows: boolean, note: string | null) =>
    api<ApiSalesImportDetail>(`${cash(c)}/sales-imports/${id}/approve`, { method: "POST", json: { version, excludeInvalidRows, note } }),
  rejectSalesImport: (c: string, id: string, version: number, reason: string) =>
    api<ApiSalesImportDetail>(`${cash(c)}/sales-imports/${id}/reject`, { method: "POST", json: { version, reason } }),
  /** Deletes the daily sales an approved import recorded; the import stays as REVERSED history. */
  reverseSalesImport: (c: string, id: string, version: number, reason: string) =>
    api<ApiSalesImportDetail>(`${cash(c)}/sales-imports/${id}/reverse`, { method: "POST", json: { version, reason } }),

  bankAccounts: (c: string) => api<ApiBankAccount[]>(`${bank(c)}/bank-accounts`),
  createBankAccount: (c: string, body: CreateBankAccountRequest) =>
    api<ApiBankAccount>(`${bank(c)}/bank-accounts`, { method: "POST", json: body }),
  updateBankAccount: (c: string, id: string, body: Omit<CreateBankAccountRequest, "accountType">) =>
    api<ApiBankAccount>(`${bank(c)}/bank-accounts/${id}`, { method: "PATCH", json: body }),
  /** Deactivates the account; its statements, transactions and history are kept. */
  deactivateBankAccount: (c: string, id: string) => api<null>(`${bank(c)}/bank-accounts/${id}`, { method: "DELETE" }),
  latestBalance: (c: string, accountId: string) => api<ApiBalance>(`${bank(c)}/bank-accounts/${accountId}/balance-snapshots/latest`),
  statementImports: (c: string) => api<ApiStatementImport[]>(`${bank(c)}/statement-imports`),
  transactions: (c: string, accountId: string) => api<ApiTransaction[]>(`${bank(c)}/bank-transactions?bankAccountId=${accountId}`),
  uploadStatement: (c: string, accountId: string, file: File) => {
    const form = new FormData()
    form.append("file", file)
    return api<ApiStatementImport>(`${bank(c)}/bank-accounts/${accountId}/statement-imports`, { method: "POST", form })
  },
  /** One ended month (YYYY-MM) of transactions from the monthly template, each with its category. */
  uploadMonthlyTransactions: (c: string, accountId: string, month: string, file: File) => {
    const form = new FormData()
    form.append("month", month)
    form.append("file", file)
    return api<ApiStatementImport>(`${bank(c)}/bank-accounts/${accountId}/monthly-imports`, { method: "POST", form })
  },
  classifyTransaction: (c: string, id: string, category: TransactionCategory, otherDescription: string | null = null) =>
    api<ApiTransaction>(`${bank(c)}/bank-transactions/${id}/classification`, { method: "PUT", json: { category, otherDescription } }),
  /** Deletes the transactions and balance the import added; the import stays listed as REMOVED. */
  removeStatementImport: (c: string, batchId: string) =>
    api<ApiStatementImport>(`${bank(c)}/statement-imports/${batchId}`, { method: "DELETE" }),
  addManualTransaction: (c: string, accountId: string, body: ManualTransactionRequest) =>
    api<ApiTransaction>(`${bank(c)}/bank-accounts/${accountId}/manual-transactions`, { method: "POST", json: body }),
  deleteManualTransaction: (c: string, accountId: string, id: string) =>
    api<null>(`${bank(c)}/bank-accounts/${accountId}/manual-transactions/${id}`, { method: "DELETE" }),
  recordManualBalance: (c: string, accountId: string, balanceAmount: number, balanceAsOf: string) =>
    api<ApiBalance>(`${bank(c)}/bank-accounts/${accountId}/manual-balances`, { method: "POST", json: { balanceAmount, balanceAsOf } }),

  cheques: (c: string) => api<ApiCheque[]>(`${payment(c)}/cheques`),
  addCheque: (c: string, body: ChequeRequest) => api<ApiCheque>(`${payment(c)}/cheques`, { method: "POST", json: body }),
  updateCheque: (c: string, id: string, body: ChequeRequest) => api<ApiCheque>(`${payment(c)}/cheques/${id}`, { method: "PUT", json: body }),
  cancelCheque: (c: string, id: string, reason: string, version: number) =>
    api<ApiCheque>(`${payment(c)}/cheques/${id}/cancel`, { method: "POST", json: { reason, version } }),
  /** The bank returned the cheque unpaid on bouncedOn; it stays on record and leaves the forecast. */
  bounceCheque: (c: string, id: string, bouncedOn: string, reason: string, version: number) =>
    api<ApiCheque>(`${payment(c)}/cheques/${id}/bounce`, { method: "POST", json: { bouncedOn, reason, version } }),

  forecastRuns: (c: string) => api<ForecastRunSummary[]>(`${forecast(c)}/forecast-runs`),
  forecastRun: (c: string, runId: string) => api<ForecastRun>(`${forecast(c)}/forecast-runs/${runId}`),
  createForecast: (c: string) => api<ForecastRun>(`${forecast(c)}/forecast-runs`, { method: "POST" }),
  forecastVsActual: (c: string, runId: string) => api<ForecastVsActual>(`${forecast(c)}/forecast-runs/${runId}/forecast-vs-actual`),
}
