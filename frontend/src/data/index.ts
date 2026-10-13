import type {
  BankAccount, BankTransaction, Cheque, DailySale, ForecastVsActual, ImportRecord, PredictedSale, Setting, User,
} from "./types"
import bankAccountJson from "./bank_account.json"
import chequesJson from "./cheques.json"
import dailySalesJson from "./daily_sales.json"
import forecastVsActualJson from "./forecast_vs_actual.json"
import importHistoryJson from "./import_history.json"
import predictedSalesJson from "./predicted_sales.json"
import settingsJson from "./settings.json"
import transactionsJson from "./transactions.json"
import usersJson from "./users.json"

/**
 * DEMONSTRATION DATA — fictional company, fictional amounts (LKR). Nothing here comes from a real bank.
 * Each export below stands in for a backend API that does not exist yet.
 */

/** "Today" for the fictional sample data. Swap for the server date once real data is loaded. */
export const TODAY = "2026-10-10"
export const NOW = "2026-10-10T17:24"

/** The company's one current bank account, or null before it is set up. */
export const bankAccount = bankAccountJson as BankAccount | null
export const cheques = chequesJson as Cheque[]
export const dailySales = dailySalesJson as DailySale[]
export const forecastVsActual = forecastVsActualJson as ForecastVsActual[]
export const importHistory = importHistoryJson as ImportRecord[]
/** Predicted cash and card sales by sales date, from TODAY onwards. Will come from the forecasting service. */
export const predictedSales = predictedSalesJson as PredictedSale[]
export const settings = settingsJson as Setting[]
export const transactions = transactionsJson as BankTransaction[]
export const users = usersJson as User[]

export * from "./types"
