# FlowLedger — frontend

Cash-flow coordination for medium-sized companies in Sri Lanka: confirmed bank position, cheque payments and a 12-week
cash-flow forecast. Not an accounting, invoicing, payroll, tax or payment system.

React + TypeScript + Vite + Tailwind CSS v4 + shadcn/ui (preset `b5dfqBy4G`, teal, 0.625rem radius).

> **All data is demonstration data** (fictional company, LKR). No bank is connected and nothing is saved — changes stay in
> the browser tab. Actions that would need a server (bank sync, invites, file validation, settings) say so instead of
> pretending to succeed (`src/lib/demo.ts`).

## Run it

Requires Node.js 20 or newer.

```bash
npm install
npm run dev        # http://localhost:5173
npm run build      # type-check + production build into dist/
npm test           # unit and render tests (Vitest)
```

## MVP screens

`/dashboard`, `/daily-sales`, `/historical-import`, `/bank-account`, `/cheques`, `/forecast` (calendar first, with Weekly
summary and Daily view), `/forecast-vs-actual`, `/users-settings`, plus `/login`.

## Roles and permissions

Defined in one place: `src/lib/permissions.ts`. Navigation hides pages a role can't open, routes show **Access denied**,
and every action checks its permission.

| Role | Pages | Actions |
|---|---|---|
| Cashier | Daily Sales only | Enter daily cash and card sales |
| Accountant | Dashboard, Daily Sales, Historical Sales Import, Bank Account, Cheque Payments, Cash-Flow Forecast, Forecast vs Actual | Enter daily sales, upload historical sales files |
| Finance Controller | All finance pages | Daily sales, upload and approve imports, add/edit/cancel cheque payments, retry bank sync; no user or role administration |
| System Admin | All pages | Everything, plus create/deactivate users, assign roles, company settings, bank configuration, access-change history |

Safeguards: nobody can change their own role or deactivate themselves, the last active System Admin can't be demoted or
deactivated, and user/role/status changes are recorded in **Users & Settings → Access changes** (in memory).

**Mock authentication.** `/login` signs in as one of the demo users (any 8+ character password); the role comes from that
user's record. The account menu can switch to another demo user. This only controls what the screen shows — it is **not
security**. The Spring Boot APIs must authenticate users and enforce the same permissions on every request.

Deferred: Scenario Analysis (`src/pages/scenarios.tsx`) is kept but not routed. Matching bank data to FlowLedger records is
left to the backend.

## Project structure

```
src/
  data/                     demonstration data (JSON) + TypeScript types — each file stands in for a backend API
  lib/forecast.ts           demonstration forecast calculation (arrival dates, weekly summary, shortage warnings)
  lib/demo.ts               "needs the backend" notice for actions that would leave the browser
  lib/format.ts             LKR / date formatting helpers
  components/ui/            shadcn/ui components
  components/app/           app shell, role access, in-memory state, data table, KPI cards
  components/charts/        Recharts charts
  pages/                    one file per screen
```

## Business rules built in

- **Actual Bank Balance** = the bank-reported balance of the current account — the only label used for it. Expected
  receipts belong to the forecast, and forecast balances are always labelled as expected/forecast.
- Only cash sales and card payments are predicted. Sales are counted on their expected **current-account arrival date**:
  Mon–Thu sales arrive the next morning, Fri–Sun sales on Monday. Sales already seen in bank data are not expected again.
- The MVP supports **one company current account**. Cash sales are deposited into it; card sales reach it as card settlement
  transfers from a separate card settlement account, which FlowLedger does not track as a company bank account.
- Cash deposits and card settlement transfers are the arrival of sales already counted — never new income.
- Only transactions that actually happened in the bank account are shown; expected money stays in the forecast.
- Cheques are simple future payments (cheque number, payee, category, amount, issue date, **expected clearing date**,
  notes). **MVP assumption: every cheque is paid on its expected clearing date.** The forecast deducts it on that date
  only — never on the issue date, never twice — and carries the balance forward. Once the date has passed the cheque is
  shown as "Assumed paid" and is part of the Actual Bank Balance, so it is not deducted again. Changing the date moves
  the deduction. Recording a cheque never changes the Actual Bank Balance.
- The only status a user can set is **Cancelled** (removed from the forecast). **Bounced** exists only as a recorded
  outcome (demo cheque #004468): never forecast and never shown as assumed paid. Automatic Cleared/Bounced detection,
  matching cheques to bank transactions and bounce handling are deferred to a later version.
- Dates are shown as **DD/MM/YYYY** everywhere (chart axes use a short DD/MM tick); values stay ISO `YYYY-MM-DD`
  internally. Browser date pickers follow the browser's own locale, so forms also show the chosen date as DD/MM/YYYY.
- A shortage is any day below LKR 0, even if the week ends positive. There is no other threshold.
- Duplicate dates block a historical import; imports affect forecasting only after Finance Controller approval.
- Duplicate bank records are never part of the Actual Bank Balance.

## Connecting the backend

All data comes from `src/data/*.json` via `src/data/index.ts`; all changes go through the actions in
`src/components/app/app-state.tsx`. Replace those with API calls, set `TODAY` from the server, and move
`buildForecast()` / `salesReceipts()` to the forecasting service.

## Adding more shadcn/ui components

```bash
npx shadcn@latest add calendar
```
