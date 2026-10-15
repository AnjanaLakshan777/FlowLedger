# FlowLedger — Architecture and Business Decisions

## 1. Product Purpose

FlowLedger helps Finance Controllers understand the confirmed bank balance,
expected cheque payments, and potential cash shortages over a rolling
12-week (84-day) forecast.

FlowLedger is not a full accounting system or ERP.

## 2. Technology and Repository

- Use a monorepo with `frontend/` and `backend/`.
- Frontend: React 19, TypeScript 5.8, Vite 6, and Tailwind CSS 4.
- Backend: Java 25, Spring Boot, and Maven.
- Keep Java 21 working for existing projects; avoid unnecessary global
  Java configuration changes.
- Build and verify one backend service at a time.
- Verify framework and dependency compatibility before adding integrations.
- Use PostgreSQL and Flyway for persistent data and schema migrations.
- Use RabbitMQ for appropriate asynchronous events and reliable event
  publishing.

## 3. Planned Backend Services

- `api-gateway`
- `identity-service`
- `cash-management-service`
- `payment-service`
- `bank-integration-service`
- `forecast-service`
- `notification-service` may be added later.

Each service owns its data. Services must not access another service's
database directly. Cross-service database foreign keys are not permitted.

The initial `identity-service` project has been generated and run.
Its business functionality and security implementation are not yet complete.

Bank accounts are owned by `bank-integration-service`, as assigned in
`docs/FlowLedger-Final-Database-Schema-v1.md`, so that later bank statement,
balance, and transaction tables can reference them within the same database.
`bank-integration-service` has been created with bank account management only
(see section 7).

`cash-management-service` has been created with daily sales management only
(see section 8).

`payment-service` has been created with cheque payment management only
(see section 9).

`forecast-service` has been created with the rolling 84-day forecast and
Forecast vs Actual only (see section 10).

`api-gateway` has been created as the single entry point for the frontend
(see section 12).

## 4. Confirmed Business Rules

### Bank Account and Forecast

- The MVP uses one company current bank account.
- Card settlements are not a separate company current account.
- The forecast horizon is 84 days.
- A cash shortage occurs when the projected closing balance is below LKR 0.

### Sales and Bank Transactions

- Record cash and card sales after the money reaches the bank.
- Keep the sale date separate from the bank transaction/deposit date.
- Actual forecast-versus-actual inflows include only cash-sales and card-sales
  money actually received into the bank, classified by actual bank
  transaction date.
- Forecast inflows estimate future cash/card receipts from historical
  bank cash-flow patterns.
- Historical bank transactions drive actual cash-flow history.
- Do not double-count sales records and corresponding bank deposits.
- Do not classify every bank credit or debit as a sale or cheque.
- Use explicit transaction classifications, including `OTHER` and
  `UNCLASSIFIED`.

### Daily Sales Records

Confirmed by the product owner on 2026-10-15.

- One daily sales record exists per company per sale date, holding both the
  cash sales amount and the card sales amount. There is no separate record
  per sales type. A second record for the same company and date is rejected
  with 409.
- Daily sales records have no bank deposit date. Deposit dates and actual
  bank inflows belong to bank transactions in `bank-integration-service`.
- A daily sales record never creates, represents, or counts as an actual
  bank inflow, including when the matching bank transaction has not yet been
  imported. Actual inflows come only from classified bank transactions and
  their actual transaction dates.
- The sale date and the actual bank transaction date are different and must
  not be assumed to be the same.
- Sale dates in the future are rejected, using `Asia/Colombo` as the business
  time zone. Past sale dates are allowed.
- Every company role (`CASHIER`, `ACCOUNTANT`, `FINANCE_CONTROLLER`,
  `COMPANY_ADMIN`) can open Daily Sales, record daily sales, and view or list
  all of its own company's daily sales records, not only the ones the user
  recorded. Nobody can access another company's records.

### Roles and Administration

Confirmed by the product owner on 2026-10-15.

- `SYSTEM_ADMIN` is a platform-level role (`users.platform_role`), separate
  from company roles. It has platform-wide authority: creating and managing
  companies, managing platform access, and platform-level settings. It may
  perform company administration (company users, roles, and bank account
  configuration) in any company where necessary.
- Without a membership in a company, a `SYSTEM_ADMIN` has administration
  access only. It cannot view or enter that company's daily sales or other
  financial business data; for that it needs a company membership like
  anyone else.
- `COMPANY_ADMIN` is a company role (`company_memberships.company_role`)
  limited to its assigned company. It manages that company's users, roles,
  settings, bank account configuration, and business operations, and never
  accesses or modifies another company's data.
- `FINANCE_CONTROLLER`, `ACCOUNTANT`, and `CASHIER` keep the permissions of
  the finalized UI permission matrix (`frontend/src/lib/permissions.ts`).
- Only a `SYSTEM_ADMIN` can create companies.
- `SYSTEM_ADMIN` can never be assigned through public self-registration or any
  other API. It is provisioned only through the restricted database procedure
  in section 6.
- All permissions are enforced by the backend services, not only by frontend
  navigation or button visibility.

### Cheques

- Supported cheque statuses are `ISSUED` and `CANCELLED`.
- Forecast outflows include issued cheques using their expected clearing dates.
- Cancelled cheques are excluded from forecasts.
- Do not automatically mark overdue cheques as paid.
- Actual outflows include cheque payments actually debited by their actual
  bank transaction date.

Confirmed by the product owner on 2026-10-15:

- Only `FINANCE_CONTROLLER` and `COMPANY_ADMIN` may add, edit, or cancel
  cheque payments, within their own company. `ACCOUNTANT` may view them;
  `CASHIER` has no access. A `SYSTEM_ADMIN` has cheque access only through a
  company membership with one of these roles, since cheques are financial
  business data (section 4, Roles and Administration).
- Every cheque has a payment category: Supplier Payment, Salary, Tax, or
  Other Expenses, as in the finalized UI. This adds a `category` column that
  schema document v1 does not have.
- Cheques are drawn on the company's active current account. The server finds
  it in `bank-integration-service`; clients do not send a bank account.
- Cancelling a cheque requires a reason, recorded with the actor in the
  payment audit history.

### Historical Sales Import

- Historical sales imports require Finance Controller approval.
- Duplicate dates block approval.
- Approved historical sales data must feed the forecast.

### Bank Holidays and Reconciliation

- Sri Lankan bank holidays must come from verified official calendar sources.
- Do not automatically shift forecast dates or amounts because of holidays
  until the business rule is decided.
- A full bank matching/reconciliation workflow is outside the current scope.
- Keep the Forecast vs Actual functionality.
- Do not add a Reconciliation UI page at this stage.

## 5. Security and Quality Requirements

- Enforce tenant isolation.
- Enforce role-based access control (RBAC) on the backend.
- Maintain audit trails for important actions.
- Protect secrets and credentials.
- Prevent duplicate transaction processing.
- Include automated tests and production-grade error handling.
- Keep services maintainable and independently responsible for their data.

## 6. Provisional Identity Rules

These rules are implemented in `identity-service` as safe defaults while
email verification, membership approval, and company-level authorization are
not available. They are not confirmed business rules and must be reviewed and
confirmed before production.

### User Self-Registration

- Self-registration permits only the `ACCOUNTANT` and `CASHIER` company
  roles. Requests for `COMPANY_ADMIN` or `FINANCE_CONTROLLER` are rejected
  with 403.
- New user accounts are created with status `PENDING`.
- New company memberships are created with status `INVITED`. `INVITED` is
  temporarily used to represent a self-registered membership awaiting
  approval. A dedicated approval-pending status may be considered before
  production.
- Registration for a non-existent or inactive company returns 404.

### Passwords and Email

- Passwords must be 12–72 characters and at most 72 UTF-8 bytes, for BCrypt
  compatibility.
- Emails are normalized to lowercase. A duplicate normalized email returns 409.
- Returning 409 for duplicate emails allows email enumeration. Rate limiting
  and email verification must be considered before production.

### Login and Access Tokens

- `POST /api/v1/identity/auth/login` takes an email and password and returns
  a JWT access token (`accessToken`, `tokenType: Bearer`, `expiresIn` in
  seconds).
- Only `ACTIVE` accounts can log in. Wrong passwords, unknown emails, and
  `PENDING`, `LOCKED`, or `DISABLED` accounts all receive the same generic
  401 response.
- Tokens are signed with HS256 and validated by Spring Security's OAuth2
  resource server (Nimbus JOSE + JWT). Signature, issuer, expiry, and the
  presence of `sub`, `iat`, and `exp` are validated.
- Tokens contain only `sub` (user ID), `iss`, `iat`, and `exp`. They carry no
  company roles or personal data. A successful login does not grant access to
  company data.
- Configuration (`application.properties`):
  - `flowledger.security.jwt.issuer`: `flowledger-identity`
  - `flowledger.security.jwt.access-token-ttl`: `15m`
  - `flowledger.security.jwt.secret`: read from the `JWT_SECRET` environment
    variable. It must be a Base64-encoded key of at least 256 bits. The
    service fails to start if it is missing or invalid; there is no
    development fallback.
- Local setup: add `JWT_SECRET` to the repository-root `.env` (see
  `.env.example`). The test suite uses its own test-only key.
- The API is stateless: no server sessions, form login, HTTP Basic, or logout
  endpoint. CSRF protection is disabled because clients send the token in the
  `Authorization` header rather than a cookie.
- Public endpoints: health, login, and user self-registration. Every other
  endpoint requires a valid bearer token. Company creation requires a
  `SYSTEM_ADMIN` (section 4).
- HS256 uses one shared secret for both signing and verification. Any service
  holding the secret could also issue tokens. As a provisional choice,
  `bank-integration-service` validates tokens with the same `JWT_SECRET`
  (see section 7). The key must stay in environment configuration and must
  not be shared with further services. Asymmetric signing (for example RS256
  or ES256 with published public keys) or gateway validation should replace
  it before production.

### Internal Membership Authorization API

- `GET /internal/v1/identity/companies/{companyId}/membership` lets other
  FlowLedger services ask whether a user may act for a company. It returns
  only `allowed`, `companyRole` (set only for an active membership), and
  `platformAdmin` (true for an active `SYSTEM_ADMIN` and an existing, active
  company, with or without a membership). Each calling service decides which
  operations `platformAdmin` permits. Callers treat an answer without
  `platformAdmin` as invalid (503), so identity-service must be deployed
  before the services that call it.
- The user is identified from their own access token, which the calling
  service forwards in the `Authorization` header and identity-service
  validates itself. The user ID is never taken from the request.
- Access is allowed only when the user's account, the company, and the
  user's membership in that company are all `ACTIVE`. Statuses are read at
  the time of each check, so locking a user or suspending a membership takes
  effect immediately, even for unexpired tokens. The check makes no database
  changes.
- Service-to-service authentication: `/internal/**` has its own security
  chain that requires the shared `IDENTITY_INTERNAL_API_SECRET` in the
  `X-Internal-Api-Key` header, compared in constant time, in addition to a
  valid user token. A missing or wrong key returns 401. The secret is separate
  from `JWT_SECRET`, must be at least 32 characters, and both services fail
  to start without it.
- The shared internal secret is an initial solution. Production-grade service
  identity (for example mTLS, or signed service credentials issued per
  service) should replace it, and `/internal/**` should not be routed through
  the public API gateway.

### Current User

- `GET /api/v1/identity/me` returns the signed-in user's ID, email, full name,
  platform role, and the memberships they can act through (active membership
  in an active company: membership ID, company ID and name, company role).
  It reads only the caller's own data; an account that is no longer `ACTIVE`
  gets 403. It exists so a client can choose the company and show the pages
  of the role; every service still checks access on each request.
- A `SYSTEM_ADMIN` without memberships sees no companies here. There is no
  API yet that lists all companies for platform administration.

### Platform and Company Administration

- `users.platform_role` is restricted to `USER` and `SYSTEM_ADMIN` by
  migration `V8__restrict_platform_roles.sql`. New users get `USER`; the
  application never writes this column.
- Roles are read from the database on every request, never from the access
  token, so removing a role or locking an account takes effect immediately.
- `POST /api/v1/identity/companies` requires an active `SYSTEM_ADMIN`
  (401 without a token, 403 for anyone else, including company admins).
- `GET /api/v1/identity/companies/{companyId}/memberships` lists a company's
  memberships; `PATCH .../memberships/{membershipId}` changes `companyRole`
  and/or `status` (`ACTIVE` or `SUSPENDED`). Allowed for an active
  `SYSTEM_ADMIN` in any company, or an active `COMPANY_ADMIN` (active account,
  company, and membership) in their own company only. Another company's
  membership ID returns 404 under the admin's own company.
- Only company roles can be assigned through this API; requesting
  `SYSTEM_ADMIN` returns 400. Nobody can change their own membership (403),
  matching the UI. The last active `COMPANY_ADMIN` of a company cannot be
  demoted or suspended (409); the company's active admins are locked during
  the change so concurrent requests cannot remove the last one.

### SYSTEM_ADMIN Provisioning Procedure

No API grants `SYSTEM_ADMIN`. An operator with direct access to the identity
database provisions it, and records who did it and when:

1. If no company exists yet (company creation itself requires a
   `SYSTEM_ADMIN`), insert the first company:
   `INSERT INTO companies (name, status) VALUES ('<name>', 'ACTIVE');`
2. The person registers through `POST /api/v1/identity/registrations` into an
   existing company, with their own password.
3. Activate the account and grant the platform role, matching the exact email:
   `UPDATE users SET account_status = 'ACTIVE', platform_role = 'SYSTEM_ADMIN' WHERE lower(email) = lower('<email>');`
4. Revoke with `UPDATE users SET platform_role = 'USER' WHERE ...`; it takes
   effect on the next request.

### Known Limitations

- Email verification and membership approval are not yet implemented.
- No flow activates `PENDING` accounts yet. Company admins can activate
  memberships, but a newly registered user can only log in after the account
  is activated directly in the database.
- User self-registration remains public. There is no self-service tenant
  onboarding: new companies are created by a `SYSTEM_ADMIN`.
- Companies can be created but not yet updated, suspended, or closed through
  the API.
- There are no refresh tokens or token revocation. An access token stays
  valid until it expires.
- Login has no rate limiting or lockout after failed attempts.
- Company creation, user registration, login, and membership or role changes
  do not yet write to `identity_audit_events`. Auditing these actions,
  especially administrative changes, remains outstanding.

## 7. Provisional Bank Integration Rules

These rules are implemented in `bank-integration-service`. They are not
confirmed business rules and must be reviewed before production.

### Bank Accounts

- `bank_accounts` is created by migration `V1__create_bank_accounts_table.sql`,
  following section 6.1 of the schema document. `company_id` is an
  identity-service ID stored without a cross-service foreign key.
- API: `POST` and `GET /api/v1/bank-integration/companies/{companyId}/bank-accounts`,
  and `GET .../bank-accounts/{accountId}`. There is no update or deactivate
  endpoint yet.
- Only the `CURRENT` account type is accepted. A company can have at most one
  `ACTIVE` `CURRENT` account, enforced by a partial unique index; a second one
  returns 409. This applies the confirmed MVP one-current-account rule strictly.
- New accounts are created `ACTIVE`. Currency uses the database default `LKR`
  and is not accepted from clients.
- Only the last four digits of the account number are accepted and stored.
  `account_number_encrypted` exists in the table but is not written until
  encryption and key management are decided. No banking credentials are stored.
- Bank account records are metadata only and do not represent a balance.
- `integrationMode` accepts the four schema values (`STATEMENT_IMPORT`,
  `BANK_API`, `SIMULATOR`, `MANUAL`), but no import or bank API exists yet.
- Local setup: port 8082; database `flowledger_bank_integration` in the
  `bank-integration-db` container on port 5434; password from
  `BANK_INTEGRATION_DB_PASSWORD`.

### Token Validation and Company Access

- The service validates identity-service access tokens with the shared HS256
  `JWT_SECRET`: signature, issuer `flowledger-identity`, required `exp` and
  `iat`, and a UUID `sub`. It has no token-issuing code.
- Every company-scoped request is authorized through identity-service's
  internal membership API (section 6), forwarding the user's token. A valid
  token alone never grants access, and company roles are not put in tokens.
- Role policy for bank accounts, following the UI permission matrix and the
  confirmed role architecture (section 4):

  | Operation | Allowed |
  |---|---|
  | List or read bank accounts | `COMPANY_ADMIN`, `FINANCE_CONTROLLER`, `ACCOUNTANT`; any active `SYSTEM_ADMIN` |
  | Create (configure) a bank account | `COMPANY_ADMIN`; any active `SYSTEM_ADMIN` |

  Configuring bank accounts is company administration, so the finance roles
  can view but not configure them. `CASHIER` has no access. A `SYSTEM_ADMIN`
  may view bank accounts without a membership because configuring requires
  seeing them; this view access is an implementation decision.
- Responses: 401 for a missing or invalid user token; 403 when the user is
  not an active member or their role is not permitted; 404 for an account
  that does not exist within the authorized company, including another
  company's account ID.
- Fail closed: if identity-service cannot be reached, times out (2 s connect,
  3 s read), returns an error, or returns an unreadable answer, the request is
  refused with 503 and nothing is changed. Access is never granted without a
  successful answer.
- Configuration: `IDENTITY_SERVICE_URL` (default `http://localhost:8080`) and
  `IDENTITY_INTERNAL_API_SECRET`. When the services run in Docker Compose,
  `IDENTITY_SERVICE_URL` must use the identity-service container name, not
  `localhost`. The application services are not yet containerized.

### Bank Statement CSV Import

- Migration `V2__create_bank_statement_import_tables.sql` creates
  `bank_statement_import_batches`, `bank_transactions`,
  `bank_balance_snapshots`, `bank_integration_audit_events`, and
  `outbox_events` (schema document section 6). Additions to the schema:
  `bank_transactions.running_balance`, `cheque_number`, `branch_code`, and
  `entry_sequence` (keeps the bank's order within a day); a
  category-direction check; and a JSON `validation_errors` column on batches.
- API under `/api/v1/bank-integration/companies/{companyId}`:
  `POST /bank-accounts/{bankAccountId}/statement-imports` (multipart part
  `file`), `GET /statement-imports`, `GET /statement-imports/{batchId}`,
  `GET /bank-transactions` (optional `bankAccountId`, `category`, `from`,
  `to`), `GET /bank-transactions/{id}`, and
  `PUT /bank-transactions/{id}/classification` (`category`, optional `reason`).
- Only one format is supported: the bank's "Transaction Details" CSV, as in
  the sample statement (format name `TRANSACTION_DETAILS_CSV`). Up to 10
  title rows before the header are allowed; columns are found by header name.
  Dates are read strictly as `M/d/yyyy`; amounts may use thousands
  separators. Each row must have exactly one amount matching its Credit or
  Debit type (the other cell blank or 0). Running Balance is required and may
  be negative. Cheque numbers are kept as text, so leading zeros remain; an
  all-zero cheque number means "no cheque". Branch ID is optional. A new bank
  format is added as another parser.
- Files: `.csv` only, at most 2 MB and 20,000 rows, UTF-8, with a CSV or
  plain-text content type. Only the cleaned file name (no client path) and
  the SHA-256 hash are stored; the file itself is not kept. Statement
  contents are never logged.
- Confirmed for this feature: one invalid row rejects the whole file; a break
  in the running-balance chain rejects the whole file; rows with amount 0 are
  skipped and counted. A rejected file returns 422 with its errors (at most
  100 are reported) and is kept as a `FAILED` batch with those errors, but no
  transactions are stored.
- File order is not assumed. Newest-first or oldest-first is taken from the
  dates, or from the balance chain when every row has the same date; if the
  order still cannot be decided, the file is rejected.
- Imports go only into the company's `ACTIVE` `CURRENT` account (404 for an
  unknown or other company's account, 409 for an inactive one). An import is
  one database transaction: batch, transactions, balance snapshot, audit
  event, and outbox events are all stored, or none are.
- Every new transaction starts as `UNCLASSIFIED` with method `UNKNOWN`.
  Nothing is classified automatically, and there is no matching,
  reconciliation, holiday shifting, or forecast calculation.

### Duplicates and Balance Snapshots

- Each transaction gets a fingerprint per account: date, direction, amount,
  running balance, normalized description, cheque number, and branch.
  Because the balance differs, legitimately repeated rows on the same day
  (for example three identical telecom payments) are kept separately; rows
  identical even in balance are told apart by their occurrence in the file.
  A unique constraint plus `ON CONFLICT DO NOTHING` skips rows already
  imported, also when two imports of the same file run at the same time.
- A file is never rejected because its hash was seen before. A repeated file
  is processed, its rows are counted as duplicates, and the response lists
  `earlierImportsOfSameFile`. Overlapping statements import only new rows.
- A balance snapshot is recorded from the newest row's running balance (by
  date and the bank's order), as of 23:59:59 `Asia/Colombo` on that date,
  with source `STATEMENT_IMPORT`. A missing balance is never treated as zero;
  the file is rejected instead. The same balance at the same time is not
  recorded twice.
- `GET .../bank-accounts/{bankAccountId}/balance-snapshots/latest` returns the
  newest snapshot (by `balance_as_of`, then the one recorded last) for
  `COMPANY_ADMIN`, `FINANCE_CONTROLLER`, and `ACCOUNTANT`, with no platform
  bypass. With no snapshot it returns 404, never a zero balance. It exists so
  forecast-service can read the confirmed balance.

### Statement Authorization, Classification, and Audit

- Role policy:

  | Operation | Allowed |
  |---|---|
  | Import a statement | `COMPANY_ADMIN`, `FINANCE_CONTROLLER` |
  | Classify a transaction | `COMPANY_ADMIN`, `FINANCE_CONTROLLER` |
  | View imports and transactions | `COMPANY_ADMIN`, `FINANCE_CONTROLLER`, `ACCOUNTANT` |

  Import and classify roles were confirmed for this feature; `ACCOUNTANT`
  view access follows the UI permission matrix and is an implementation
  decision. `CASHIER` has no access. Statements are business data, so a
  `SYSTEM_ADMIN` needs a company membership; there is no platform bypass
  here (unlike viewing and configuring bank accounts).
- Categories: `CASH_SALES_DEPOSIT` and `CARD_SALES_SETTLEMENT` only for
  credits, `CHEQUE_PAYMENT` only for debits, `OTHER` and `UNCLASSIFIED` for
  both. This is checked by the API (400) and by a database constraint.
- Audit events (`bank_integration_audit_events`, append-only like the
  payment audit table): `BANK_STATEMENT_IMPORTED` and
  `BANK_STATEMENT_REJECTED` with batch counts, and `TRANSACTION_CLASSIFIED`
  with the actor, reason, and before/after classification. Classifying with
  the unchanged category writes nothing.
- Outbox events in the same transaction: `BankTransactionImported`,
  `BankBalanceSnapshotRecorded`, and `BankTransactionCategoryChanged`, with
  IDs, dates, amounts, and category but no description.

### Known Limitations

- Every company-scoped request makes a synchronous call to identity-service,
  so bank account endpoints are unavailable while identity-service is down.
  There is no caching of membership answers.
- The role policy is enforced in bank-integration-service; there is no
  shared permission model across services yet.
- Bank account changes do not yet write to `bank_integration_audit_events`.
- No outbox publisher yet, so the forecast does not receive bank events.
- Fingerprints rely on the running balance. A file without balances, or a
  bank that re-sends a row with a corrected balance or description, would
  not be recognised as a duplicate.
- `BankAccountService` still makes its identity call inside the database
  transaction; the statement services do not.
- Transaction and import lists are not paginated; statement batch statuses
  `UPLOADED` and `VALIDATING` are not used (imports are synchronous).
- The test resources contain a copy of the sample bank statement, which may
  hold real account data.

## 8. Provisional Cash Management Rules

These rules are implemented in `cash-management-service`. They are not
confirmed business rules unless they restate section 4, and must be reviewed
before production.

### Daily Sales

- `daily_sales` is created by migration `V1__create_daily_sales_table.sql`,
  following section 4.1 of the schema document. `company_id` and
  `recorded_by` are identity-service IDs stored without cross-service foreign
  keys.
- API: `POST` and `GET /api/v1/cash-management/companies/{companyId}/sales`
  (optional inclusive `from` and `to` sale-date filters, newest first), and
  `GET .../sales/{saleId}`. There is no update or delete endpoint.
- One record per company per sale date, holding `cashSalesAmount` and
  `cardSalesAmount` (`NUMERIC(19,2)`, each at least 0, at most two decimal
  places). Their total must be greater than 0. A second record for the same
  date returns 409, from the schema's `UNIQUE (company_id, sale_date)`. This
  is separate from the duplicate-date rule for historical import approval.
- Past sale dates are accepted. Sale dates after today in `Asia/Colombo` are
  rejected with 400 (confirmed rule, section 4).
- The company comes from the request path, the recording user from the access
  token, and `recorded_at` from the database. Clients cannot set them.
- Only manual entry (`entry_source = 'UI'`) is allowed. `HISTORICAL_IMPORT`
  and `import_batch_id` will be added with the historical import feature, so
  imported rows cannot bypass import approval.

### Sale Date, Deposit, and Inflows

- Users record sales after the money has been deposited. For example, sales
  of 50,000 on 2026-10-03 are entered on 2026-10-04, the day the money is
  credited to the bank. The record stores `sale_date` 2026-10-03; `recorded_at`
  stores the entry time.
- `daily_sales` has no bank deposit date, as in the schema document. Creating
  a sales record does not create a bank transaction or count as a bank inflow.
  The inflow and its date come from the bank transaction (for example
  `CASH_SALES_DEPOSIT` on 2026-10-04) in `bank-integration-service`, so the
  same money is not counted twice.

### Authorization

- Every request is authorized through identity-service's internal membership
  API, in the same way as bank accounts (section 7): active user, active
  company, active membership, and a permitted role. Identity-service failures
  return 503.
- All four company roles may list, read, and create daily sales for their own
  company (confirmed rule, section 4). A `SYSTEM_ADMIN` without a membership
  in the company has no access to its daily sales (section 4).
- The membership check finishes before any database transaction starts, so a
  slow identity-service does not hold database connections. Access is checked
  before request-specific validation such as the date filter, so a user
  without access always receives 403.
- Local setup: port 8081; database `flowledger_cash_management` in the
  `cash-management-db` container on port 5435; password from
  `CASH_MANAGEMENT_DB_PASSWORD`.

### Known Limitations

- Recorded sales cannot be corrected. The table has `updated_by`,
  `updated_at`, and `version` for later corrections, but no update rules are
  defined yet.
- Sales changes do not yet write to `cash_management_audit_events`, and there
  is no outbox. A comprehensive audit-event implementation remains outstanding.
- Listing is not paginated.
- The JWT validation, identity client, and authorization code is duplicated in
  `bank-integration-service` and `cash-management-service`.

## 9. Provisional Payment Rules

These rules are implemented in `payment-service`. Rules not stated in section 4
follow the finalized UI and must be reviewed before production.

### Cheques

- Migration `V1__create_cheque_tables.sql` creates `cheques`,
  `payment_audit_events`, and `outbox_events` (schema document section 5, plus
  `category`). `company_id`, `bank_account_id`, and user IDs are stored without
  cross-service foreign keys.
- API under `/api/v1/payment/companies/{companyId}/cheques`: `POST` (add),
  `GET` (list, optional `status`, `clearingFrom`, `clearingTo`), `GET /{id}`,
  `PUT /{id}` (edit), and `POST /{id}/cancel` (`reason`, `version`).
- Validation follows the UI: a 6-digit cheque number (kept as text, so leading
  zeros are preserved), amount greater than 0 with at most two decimal places,
  expected clearing date not before the issue date and after today in
  `Asia/Colombo`. A cheque number can be used once per company current
  account (409), including after cancellation.
- Only an `ISSUED` cheque whose expected clearing date is after today can be
  edited or cancelled (409 otherwise), matching the UI. A cheque past its
  expected clearing date stays `ISSUED`; nothing marks it paid.
- Edits and cancellations carry the cheque's `version`. A stale version, or a
  concurrent change, returns 409 instead of overwriting another user's change.
- If the company has no active current account, adding a cheque returns 409.
  If `bank-integration-service` cannot answer, it returns 503.

### Audit History and Outbox

- Every add, effective edit, and cancellation writes a `payment_audit_events`
  row (`CHEQUE_ISSUED`, `CHEQUE_UPDATED`, `CHEQUE_CANCELLED`) with the actor,
  before and after state, and the cancellation reason, in the same transaction
  as the change. An edit that changes nothing writes no event.
- `payment_audit_events` is append-only: database triggers reject `UPDATE`,
  `DELETE`, and `TRUNCATE`, and the application has no update or delete code.
  The database user is a superuser and could bypass the triggers; production
  needs a separate runtime role without that power.
- The same transaction writes a `ChequeIssued`, `ChequeUpdated`, or
  `ChequeCancelled` row to `outbox_events`, with cheque IDs, amount, dates,
  status, and version but no payee or notes.

### Outbox Publishing (RabbitMQ)

- Confirmed for this milestone: payment-service publishes its outbox to the
  local RabbitMQ 4 broker (`rabbitmq` in compose.yaml), on the durable topic
  exchange `flowledger.payment`. Routing keys: `ChequeIssued` →
  `cheque.issued`, `ChequeUpdated` → `cheque.updated`, `ChequeCancelled`
  → `cheque.cancelled`. An unknown event type is never routed by guess; it
  stays pending.
- Each message carries the stored JSON payload unchanged (no payee or notes),
  is persistent, has the outbox event ID as its AMQP message ID, and has the
  headers `eventType`, `schemaVersion`, `aggregateType`, `aggregateId`,
  and `companyId`.
- Delivery is at least once. An event is marked `published_at` only after a
  publisher confirm (ack) for a message the broker could route (mandatory
  publishing; a returned message counts as a failure). A nack, no confirm
  within 10 s, an unroutable message, or an unreachable broker keeps the event
  pending with `attempt_count`, `next_attempt_at`, and a short sanitized
  `last_error` (no payload, no stack trace). Events are never dropped.
- Retries use capped exponential backoff with equal jitter: half to all of
  5 s × 2^(attempt − 1), capped at 15 minutes.
- Claiming: one short statement reserves a batch with `FOR UPDATE SKIP
  LOCKED` and a 60 s lease (`next_attempt_at` moves past the lease and the
  attempt is counted). Publishing happens outside any database transaction;
  marking the result is another short statement. A crash during an attempt
  leaves the event leased; when the lease expires it is claimed again and
  sent with the same message ID. Several publishers can run at once.
- Order: only the oldest pending event of each cheque is claimed, so one
  cheque's events are published in order; a pending earlier event holds back
  later ones of the same cheque. Consumers must still check `version`.
- Activation boundary: migration `V2__add_outbox_publishing_boundary.sql`
  adds `publishable` with default `false`, then changes the default to
  `true`. Existing rows are not rewritten (PostgreSQL stores the default in
  the catalogue) and read `false`; every event written afterwards is
  `true`. The publisher only claims `publishable` rows, so the seven
  development events written before the migration were never published,
  edited, or deleted (verified with a checksum of every column before and
  after). A `created_at` cut-off was rejected: `created_at` is the
  transaction start time, so an event committed just after a cut-off could be
  skipped for good.
- `OUTBOX_PUBLISHER_ENABLED=false` pauses the scheduled publisher (default
  `true`); events written meanwhile are published once it is enabled again.
  It is not the activation boundary.

### Internal Cheque API

Confirmed for this milestone (Feature 2, milestone 1): a read-only API for
forecast-service's cheque projection backfill and verification. Not used by
anything yet.

- Authentication: a separate `/internal/**` security chain, ordered before
  the public one, accepts only the header `X-Internal-Api-Key` with
  `PAYMENT_INTERNAL_API_SECRET`, compared as SHA-256 digests in constant
  time (the identity-service pattern). User access tokens are not accepted
  there, alone or as a substitute; the caller is a service job, not a person,
  so no company membership is checked. The key is a new secret, not
  `IDENTITY_INTERNAL_API_SECRET`: only payment-service and forecast-service
  should hold it, so a key leaked from another service does not expose every
  company's cheques.
- Fail closed: without the secret, payment-service starts (the public API
  does not need it) but answers every internal request with 401 and logs a
  warning. A secret shorter than 32 characters, or equal to
  `IDENTITY_INTERNAL_API_SECRET`, stops startup. A wrong or missing key is
  401 with a fixed body.
- The API gateway does not route `/internal/**` (covered by the gateway
  tests). Only services on the private network can reach it.
- `GET /internal/v1/payment/cheques?afterId={uuid}&limit={n}` returns
  `{"items": [...], "nextAfterId": uuid | null}`: cheques of all companies,
  all statuses and all clearing dates, ordered by `id`, with `id >
  afterId` (from the start when `afterId` is omitted). `limit` defaults
  to 100 and must be 1 to 500; anything else, or a malformed `afterId`, is
  400. `nextAfterId` is the last item's ID when more cheques followed at
  the time of the read, otherwise null; a full last page therefore ends
  with null, without an extra empty request.
- Item fields, named and formatted as in the cheque event payload:
  `chequeId`, `companyId`, `bankAccountId`, `chequeNumber`, `category`,
  `amount` (plain decimal string, two decimals), `issueDate`,
  `expectedClearingDate` (ISO dates), `status`, and `version` (the same
  column the events carry). No payee, notes, creator, or timestamps.
- Order: PostgreSQL compares UUIDs as unsigned bytes, which equals the
  order of their lowercase text. Java's `UUID.compareTo` compares signed
  halves and orders differently; clients must pass `nextAfterId` back
  unchanged and never compare cursors with it.
- `GET /internal/v1/payment/cheques/summary[?companyId={uuid}]` returns
  `{"chequeCount": n, "companies": [{"companyId", "chequeCount",
  "issuedCount", "cancelledCount", "versionSum"}]}`, one entry per company
  with cheques (ascending by ID), or only the given company; a company
  without cheques has no entry. All numbers come from one SQL statement, so
  they are consistent with each other at one moment. Forecast-service gets
  the company list from here and does not need identity-service.

Completeness and consistency (what is guaranteed and what is not):

- Each page and each summary is one statement and therefore internally
  consistent. A whole scan is **not** a snapshot: pages are separate
  requests and transactions.
- IDs are random (`gen_random_uuid()`), so a cheque committed during a scan
  with an ID below the cursor is not returned by that scan; one above the
  cursor is. A cheque changed during the scan is returned at whatever
  version the page read. No cheque is returned twice by one scan.
- Cheques are never deleted and their version only grows, so a client can
  prove completeness: read its own copy's count and version sum for a
  company first, then the summary. Its copy is complete and current exactly
  when both match, provided every cheque in its copy exists in
  payment-service with that company at a version no higher than here (true
  for a copy filled only from this API and the cheque events). A mismatch
  means a cheque was missed or changed meanwhile: rescan, or wait for the
  events, and compare again. Nothing is silently omitted if the client
  follows this check.
- Cheques missed by a scan also reach forecast-service as outbox events,
  because every change written after the outbox activation boundary has
  one; the summary check covers the case that they have not arrived yet.

### Authorization and Dependencies

- Calls to identity-service and bank-integration-service finish before any
  database transaction starts. Both fail closed (503).
- Local setup: port 8083; database `flowledger_payment` in the `payment-db`
  container on port 5436; password from `PAYMENT_DB_PASSWORD`;
  `BANK_INTEGRATION_SERVICE_URL` (default `http://localhost:8082`).

### Known Limitations

- The only consumer is forecast-service (section 10, Cheque Event Consumer).
  Its queue is declared when forecast-service first connects to the broker;
  until then every new cheque event is unroutable and stays pending, retried
  with backoff. Nothing is lost, but nothing is delivered either.
- Every consumer must deduplicate on the message ID (at-least-once delivery)
  and ignore older cheque versions, as forecast-service does.
- No alert yet for events that keep failing to publish; they stay pending and
  are visible through `attempt_count` and `last_error`.
- No audit-history read API.
- The public cheque list is not paginated (the internal scan is).
- The internal API trusts any holder of the key with every company's
  cheques; there is no per-caller identity, scope, rate limit, or audit log
  of internal reads. The summary lists all companies in one response.
- The JWT validation, identity client, and authorization code is also
  duplicated here.

## 10. Provisional Forecast Rules

These rules are implemented in `forecast-service`. Rules not stated in section
4 are implementation decisions and must be reviewed before production; the
forecasting method in particular is a placeholder until the business rule is
confirmed.

### Runs and Inputs

- Migration `V1__create_forecast_tables.sql` creates `forecast_model_configs`,
  `forecast_runs`, `forecast_daily_results`, and `forecast_run_cheques`
  (schema document section 7). Additions: `bank_account_id`, `currency`,
  `balance_stale`, `inflow_estimate_status`, and `data_quality` on runs;
  `expected_cheque_count` on days; `source_version` on run cheques. Expected
  inflow columns are nullable (schema v1: NOT NULL default 0), because
  "not estimated" must not be stored as zero.
- Not created yet: the bank and sales projection tables, `calendar_holidays`
  (no verified holiday source), and an audit or outbox table (schema section
  9 removes them from the MVP). `forecast_cheques` and `processed_events`
  were added later (see Cheque Event Consumer); runs still read their inputs
  through the APIs.
- API under `/api/v1/forecast/companies/{companyId}/forecast-runs`: `POST`
  (create a run), `GET` (list), `GET /{runId}` (run with its 84 days and the
  cheques used), and `GET /{runId}/forecast-vs-actual`.
- Inputs are read with the user's token from the owning services, never
  from their databases: the active current account, its latest balance
  snapshot, the statement import batches, and the bank transactions from
  bank-integration-service; `ISSUED` cheques from payment-service. All calls
  finish before the run's database transaction; open-in-view is off, so no
  connection is held while they run. Daily sales from cash-management are
  not used, so they cannot be counted twice.
- Fail closed: if identity-service, bank-integration-service, or
  payment-service cannot answer, answers with an error, times out, or returns
  incomplete data, the request returns 503 and nothing is saved. No active
  current account, or no confirmed LKR balance, returns 409; a missing
  balance is never replaced by zero.
- A run is saved complete and never changed (triggers reject `UPDATE`,
  `DELETE`, `TRUNCATE`). It stores the cheques it deducted, a copy of the
  configuration, and a SHA-256 digest of all inputs. Requesting a forecast
  again on the same day with unchanged inputs returns the existing run
  (200) instead of saving a copy, also for two requests at the same time.
- The first run of a company creates configuration version 1 with method
  `HISTORICAL_BANK_CASH_FLOW_PATTERN`, 84 days, and LKR 0 minimum balance.
  There is no API to change it yet.

### Calculation

- The horizon is exactly 84 days and starts the day after the confirmed
  balance's date (`Asia/Colombo`). Day 1 opens with the confirmed balance;
  each later day opens with the previous day's closing balance. A day is a
  cash shortage when its closing balance is below LKR 0; negative balances
  are allowed throughout.
- Expected outflows: `ISSUED` cheques on the current account whose expected
  clearing date is within the horizon, on that date. `CANCELLED` cheques
  never count. Because the horizon starts after the balance date, a cheque
  expected after the balance date is never dropped. Cheques expected on or
  before the balance date are not deducted and are not marked paid either.
- Expected inflows (strategy `WEEKDAY_AVERAGE`, behind a replaceable
  interface): for each weekday, the average of the `CASH_SALES_DEPOSIT`
  credits, and separately of the `CARD_SALES_SETTLEMENT` credits, on the same
  weekday in the 8 weeks up to the balance date, by actual transaction date,
  rounded half-up to cents. Only days covered by an imported statement are
  averaged; a covered day without such credits counts as 0, an uncovered day
  is left out. `OTHER` and `UNCLASSIFIED` transactions are never counted.
- If any weekday has fewer than 4 statement-covered days in that window, no
  inflows are estimated: the run is `INSUFFICIENT_HISTORY`, inflow amounts
  are null, and balances include cheque payments only.
- Labels shown with each run: `BALANCE_STALE` when the balance is more than
  3 days older than the day of the run (the forecast still starts from it);
  `INSUFFICIENT_HISTORY`; and `INCOMPLETE_CLASSIFICATION` when the history
  has `UNCLASSIFIED` credits. Lookback (8 weeks), minimum history (4 days
  per weekday), and stale threshold (3 days) are stored configuration
  values, not confirmed rules.

### Forecast vs Actual

- The client chooses the saved run to compare (the baseline-run rule is
  open). Actual inflows are `CASH_SALES_DEPOSIT` and `CARD_SALES_SETTLEMENT`
  credits; actual outflows are `CHEQUE_PAYMENT` debits; both by actual bank
  transaction date. `OTHER` and `UNCLASSIFIED` are in neither total, and
  unclassified transactions are counted and labelled.
- Only days covered by an imported statement, up to today, have actual
  values; other days are null, not zero. The actual closing balance is the
  run's opening balance plus every actual transaction since, and stops at
  the first day without statement coverage.
- Differences are actual minus forecast. If the run did not estimate
  inflows, inflow differences are null.

### Cheque Event Consumer (RabbitMQ)

- Confirmed for this milestone: forecast-service consumes payment-service's
  `ChequeIssued`, `ChequeUpdated`, and `ChequeCancelled` events and keeps
  its own copy of the cheques in `forecast_cheques`. It never reads
  payment-service's database. Forecast calculations, saved runs, and their
  cheque snapshots are unchanged and do not read the projection yet; a run
  still reads cheques from the payment API.
- Migration `V2__create_cheque_projection_and_processed_events.sql` (additive)
  creates `forecast_cheques` (one row per cheque: company, bank account,
  number, category, amount, issue and expected clearing dates, status,
  `source_version`, `source_event_created_at`, `last_event_id`,
  `projected_at`) and `processed_events` (event ID, source service, event
  type, time, and a result summary with the outcome, cheque ID, and version
  only). Payee and notes are not in the events and are not stored.
- Timestamps: `source_event_created_at` is the AMQP timestamp, i.e. when
  payment-service wrote the outbox event (its transaction start). It is not
  the cheque's business update time, which the events do not carry, so the
  schema document's `source_updated_at` is not used. It is null if a
  message has no timestamp. `projected_at` is when forecast-service applied
  the event.
- Topology (declared by forecast-service, names configurable): binding
  `cheque.*` from the durable topic exchange `flowledger.payment` to the
  durable quorum queue `forecast.payment-cheque-events`, whose dead-letter
  exchange `forecast.payment-cheque-events.dlx` routes to the quorum queue
  `forecast.payment-cheque-events.dlq`. The queue's delivery limit (5) moves
  a message that keeps coming back unacknowledged, for example because the
  service crashes on it, to the dead-letter queue.
- Validation, before anything is written: `schemaVersion` header exactly 1;
  `eventType` one of the three types (and equal to the AMQP type when
  present); JSON content; message ID, `aggregateId`, `companyId`,
  `chequeId`, and `bankAccountId` are UUIDs; payload `chequeId` equals
  `aggregateId` and payload `companyId` equals the `companyId` header;
  cheque number digits only; known category; amount a positive plain decimal
  string with at most 2 decimals that fits NUMERIC(19, 2); ISO dates with the
  clearing date not before the issue date; status `CANCELLED` for
  `ChequeCancelled` and `ISSUED` otherwise; integer `version` of at least
  1; body at most 16 KB. Unknown extra fields are ignored and never stored.
- One transaction per event: insert the event ID into `processed_events`
  (`ON CONFLICT DO NOTHING`; already there means a duplicate and nothing
  else happens), then insert or update the cheque only if the stored
  `source_version` is lower and the stored company is the same, then store
  the outcome. Outcomes: `APPLIED`, `STALE` (the same or a newer version is
  already projected; nothing changes), `DUPLICATE`. A cheque already
  projected for another company is rejected and the transaction rolled back.
  The event ID's primary key and the cheque row lock serialise concurrent
  deliveries of the same event or cheque.
- Acknowledgement is manual and only after the commit. Applied, stale, and
  duplicate events are acknowledged. If the acknowledgement is lost after
  the commit, the redelivered event is found in `processed_events` and
  acknowledged as a duplicate.
- Invalid or unsupported messages and tenant conflicts are rejected without
  requeue and go to the dead-letter queue at once. Other failures (for
  example the database being unavailable) are retried in the listener up to
  4 attempts with 500 ms, 1 s, 2 s backoff (capped at 5 s), each attempt in a
  new transaction, then dead-lettered. Messages are never requeued in a loop;
  only a shutdown during the backoff returns the message to the queue.
- Logs contain only the message ID, event type, cheque ID, outcome, and a
  short reason (a validation message naming the field, or an exception class
  name); never payload values, amounts, cheque numbers, or database error
  text.
- `CHEQUE_EVENTS_CONSUMER_ENABLED=false` stops the listener (default
  `true`). If RabbitMQ is down, forecast-service still starts and serves
  forecasts; the listener keeps reconnecting.

### Cheque Projection Backfill

The consumer only sees events published after payment-service's activation
boundary; cheques created earlier (and the seven unpublished development
events) are not in the projection. Confirmed for Feature 2, milestone 2: a
resumable backfill from payment-service's internal scan (section 9,
Internal Cheque API). Forecasts still read cheques from the payment API;
the projection is not verified and not used by calculations.

- Migration `V3__add_cheque_backfill_state.sql` (additive; V1 and V2 are
  unchanged): `forecast_cheques.last_event_id` becomes nullable and a new
  `projection_source` (`EVENT` or `BACKFILL`, existing rows `EVENT`) says
  which path wrote the current version; a check keeps `EVENT` rows with an
  event ID and `BACKFILL` rows without one. New tables
  `cheque_backfill_runs` (status `IN_PROGRESS` or `COMPLETED`, cursor,
  pages, counts of seen / applied / stale / rejected cheques, failure count,
  last short error, times; at most one run in progress) and
  `cheque_backfill_rejections` (run, cheque and company IDs, reason).
- A run reads pages of `flowledger.cheque-backfill.page-size` (200) with
  the `PAYMENT_INTERNAL_API_SECRET` key, starting after its saved cursor.
  Each page is read over HTTP outside any transaction and checked against
  the scan contract (ascending in the database's UUID order, after the
  cursor, within the limit, `nextAfterId` equal to the last item); a page
  that breaks it is a failure and never moves the cursor.
- One transaction per page locks the run row, checks that the cursor has
  not moved (otherwise another worker applied the page and it is dropped),
  applies every item, records rejections, and moves the cursor and the
  counters. A page is therefore applied completely or not at all, and a
  restart neither skips nor double-counts it. A run that reaches the end of
  the scan is `COMPLETED`; this only means the scan ended, not that the
  projection is complete.
- Items are validated with the same rules as event payloads (shared
  `ChequeFields`). Each one goes through the projection's guarded upsert:
  insert, or update only when the stored `source_version` is lower and the
  company is the same. Backfilled rows get `projection_source = BACKFILL`,
  no event ID, and no event time. An invalid item, or a cheque already
  projected for another company, is recorded in
  `cheque_backfill_rejections` (IDs and a reason naming the problem, never
  field values) and the run continues; the rejection count stays on the
  run for the later verification step.
- Version handling, both directions: the backfill never replaces the same
  or a newer version, whichever path wrote it; an event replaces a
  backfilled row only with a higher version and then sets `EVENT` and its
  event ID; an event with the same or a lower version is `STALE` and
  acknowledged. An event applied after a page was read but before it was
  applied wins, because the page's upsert then finds the higher version
  (tested). Backfill rows are not written to `processed_events`, so later
  events for the same cheque are still applied. Equal versions are treated
  as equal data, which relies on payment-service raising `version` on every
  change.
- Failures (payment-service unreachable or answering an error, invalid
  page, database error in the page transaction) are retried from the same
  cursor with capped exponential backoff (1 s doubling to 30 s), up to
  `max-attempts` (5) per page. The run then stops with `last_error` set
  (an HTTP status, a contract message, or an exception class; never bodies
  or secrets) and stays `IN_PROGRESS`; the next start resumes it. Starting
  again after a completed run begins a new run from the start; it changes
  nothing that is already current.
- Starting: `CHEQUE_BACKFILL_ON_STARTUP=true` starts or resumes a run in a
  background thread after startup (default `false`; startup does not wait,
  and a failure does not stop the service). Without
  `PAYMENT_INTERNAL_API_SECRET` the backfill refuses to start and creates
  no run. Several instances can start it at once: they share the one run in
  progress, and the run-row lock lets only one apply each page.
- Logs contain run IDs, counts, and short reasons only.
- Not done yet (milestone 3): comparing the projection with payment-service's
  summary, a verified state, recording dead-lettered events in the database,
  and switching forecasts to the projection.

### Authorization

- `COMPANY_ADMIN`, `FINANCE_CONTROLLER`, and `ACCOUNTANT` may create, list,
  and read forecasts and Forecast vs Actual (the roles that see the forecast
  pages in the UI permission matrix). `CASHIER` has no access. A
  `SYSTEM_ADMIN` needs a company membership, since forecasts are financial
  business data. Letting `ACCOUNTANT` create runs is an implementation
  decision: a run only reads data.
- Responses: 401 without a valid token, 403 without permission, 404 for a
  run that is not in the authorized company.
- Local setup: port 8084; database `flowledger_forecast` in the
  `forecast-db` container on port 5437; password from `FORECAST_DB_PASSWORD`;
  `BANK_INTEGRATION_SERVICE_URL` (default `http://localhost:8082`) and
  `PAYMENT_SERVICE_URL` (default `http://localhost:8083`).

### Known Limitations

- A forecast needs four synchronous service calls plus the identity check;
  it is unavailable while any of these services is down.
- Bank transactions, imports, and cheques are read without pagination; long
  histories make larger responses.
- Automated tests use fakes for the other services; the full flow was also
  run once by hand against the running services (identity, bank-integration,
  payment, forecast, cash-management) with test data.
- Runs are created on request only; there are no scheduled or data-change
  runs and no outbox events.
- The cheque projection is not used by calculations and is not verified;
  until a backfill run has completed it only holds cheques changed after
  payment-service's outbox activation boundary. A completed run is not
  proof of completeness (scan consistency limits, rejected items, events
  still pending in payment-service's outbox or in the dead-letter queue).
  The backfill has no API or UI to start it, show progress, or list
  rejections; it is started by configuration and read from the database.
  A rejected item is not retried by the same run, only by a new run.
  Dead-lettered messages are only stored; there is no alert,
  inspection API, or replay tool. Only one consumer instance was tested
  (more instances are safe through the database guards, but per-cheque order
  is then only protected by the version check). Bank-integration events are
  not consumed.
- The JWT validation, identity client, and authorization code is duplicated
  here as well.

## 11. Frontend Integration

The React frontend reads live data from the services by default. The
fictional data in `frontend/src/data` is used only by the frontend tests and
by an explicit demo mode (`VITE_DEMO_MODE=true`), which shows a
"Demonstration data" banner. A failed request is shown as an error with a
retry; the frontend never falls back to demonstration data.

### Connection and Sessions

- All requests go through the API gateway (section 12). In development the
  frontend calls same-origin `/api/v1/...` paths and the Vite server forwards
  `/api` to the gateway (`API_GATEWAY_URL`, default
  `http://localhost:8088`). A frontend served from another origin sets
  `VITE_API_BASE_URL` to the gateway URL and relies on the gateway's CORS.
- Sign-in uses the identity login API. The access token is kept in session
  storage, or local storage when "Keep me signed in" is chosen; a 401 from
  any service ends the session. The browser never holds service secrets.
- After sign-in, `GET /me` gives the company and role. A backend
  `COMPANY_ADMIN` uses the UI's administrator permission set and is labelled
  "Company Admin"; a user with several memberships can switch company in the
  header; a user with no membership sees a "No company access" screen. The UI
  only hides what a role cannot use; the services enforce every permission.

### Screens

- Live data: Dashboard, Daily Sales, Bank Account, Cheque Payments,
  Cash-Flow Forecast (calendar, weekly and daily views), and Forecast vs
  Actual.
- The forecast screens show the backend's results only; the browser does not
  calculate a forecast. They show the backend's horizon and start date (the
  day after the confirmed balance date), its stale-balance, insufficient
  history, and unclassified-transaction labels, and no likely-range band
  (the backend does not estimate one). Weeks are 7-day periods from the
  forecast start. "Update forecast" asks the forecast service for a run.
- Cheques are never shown as paid: a Scheduled cheque past its expected
  clearing date is labelled "Past clearing date". The frontend rule that
  treated such cheques as paid was removed. Cancelling requires a reason.
- A missing confirmed balance is shown as "No confirmed balance yet", never
  as 0. Expected receipts that were not estimated are shown as "Not
  estimated".
- Forecast vs Actual compares one saved run, chosen by the user (newest
  first by default), because the baseline-run rule is open.
- Not connected (no backend API yet), showing a "Not connected" notice
  instead of demonstration data: Historical Sales Import and Users &
  Settings.

### Known Limitations

- Daily sales have no note and no recorder name in the API; the screen shows
  "You" or "Another user", and a recorded date cannot be changed.
- There is no statement upload screen and no bank account configuration
  screen yet, although both APIs exist.
- Bank transactions have no value date in the API.

## 12. API Gateway and Environment Configuration

### API Gateway

- `backend/api-gateway` (port 8088) uses Spring Cloud Gateway Server WebMVC
  5.0.3 from the Spring Cloud 2025.1.3 release train, which lists Spring Boot
  4.0.x and 4.1.x as compatible. It is servlet-based like the services.
- Routes keep each service's own prefix, so paths and query strings are
  forwarded unchanged: `/api/v1/identity/**` (8080),
  `/api/v1/cash-management/**` (8081), `/api/v1/bank-integration/**` (8082),
  `/api/v1/payment/**` (8083), `/api/v1/forecast/**` (8084). Service URLs
  come from `IDENTITY_SERVICE_URL`, `CASH_MANAGEMENT_SERVICE_URL`,
  `BANK_INTEGRATION_SERVICE_URL`, `PAYMENT_SERVICE_URL`, and
  `FORECAST_SERVICE_URL`. Anything else, including `/internal/**`, is not
  routed (404).
- The gateway does not authenticate or authorize. It forwards the
  `Authorization` header unchanged, and every service still validates the
  token and checks company membership and role on each request, so the
  gateway cannot bypass tenant isolation or roles. The gateway holds no JWT
  key or internal secret, and it removes any `X-Internal-Api-Key` header
  sent by a client.
- Request bodies are streamed unchanged; multipart uploads are not parsed
  by the gateway, and upload limits stay with the services. Spring may add a
  `charset` parameter to the forwarded `Content-Type`; the media type and
  multipart boundary are unchanged.
- Timeouts: 2 s to connect, 30 s to answer. A service that cannot be reached
  returns 503 and one that does not answer in time returns 504, both as
  problem details naming the service; answers from the services, including
  their 401, 403, and 503, are passed through unchanged. Log lines contain
  the method and path only, never headers or tokens.
- Health: `GET /api/v1/gateway/health`; each service's health stays at
  `/api/v1/<service>/health` through the gateway.
- CORS: only the origins in `GATEWAY_CORS_ALLOWED_ORIGINS` (default
  `http://localhost:5173`) may call the API from a browser, with the
  `Authorization` and `Content-Type` headers and without credentials
  (tokens are never sent as cookies).
- Docker Compose: `api-gateway` is in the optional `gateway` profile
  (`docker compose --profile gateway up -d api-gateway`, after building the
  jar). Its container reaches the services running on the host through
  `host.docker.internal` and receives only the service URLs and allowed
  origins.

### Environment Configuration

- One git-ignored `.env` in the repository root holds local secrets and
  settings; it is read by Docker Compose, every backend service, and (for
  `API_GATEWAY_*` only) the Vite dev server. `.env.example` lists every
  variable with placeholders and short headings only.
- Names follow `<SERVICE>_DB_PASSWORD` / `_DB_URL` / `_DB_USERNAME` for
  databases and `<SERVICE>_SERVICE_URL` for service locations. identity-service
  now reads `IDENTITY_DB_URL`, `IDENTITY_DB_USERNAME`, and
  `IDENTITY_DB_PASSWORD` (previously `DB_URL`, `DB_USERNAME`, and
  `DB_PASSWORD`, which duplicated the same password).
- Added: `CASH_MANAGEMENT_SERVICE_URL`, `FORECAST_SERVICE_URL`,
  `GATEWAY_CORS_ALLOWED_ORIGINS`, `API_GATEWAY_URL` (all optional, with
  defaults), and the frontend build variable `VITE_API_BASE_URL`. `VITE_`
  variables are visible in the browser and must never hold secrets.
- RabbitMQ: `RABBITMQ_USERNAME` and `RABBITMQ_PASSWORD` (required by
  compose.yaml, payment-service, and forecast-service), optional
  `RABBITMQ_HOST`, `RABBITMQ_PORT`, `OUTBOX_PUBLISHER_ENABLED`
  (payment-service), and `CHEQUE_EVENTS_CONSUMER_ENABLED` (forecast-service).
- `PAYMENT_INTERNAL_API_SECRET`: key for payment-service's internal API, at
  least 32 characters and different from `IDENTITY_INTERNAL_API_SECRET`;
  forecast-service uses the same value for the cheque backfill. Optional:
  without it payment-service refuses every internal request and
  forecast-service's backfill refuses to start; nothing else needs it.
- `CHEQUE_BACKFILL_ON_STARTUP` (forecast-service, default `false`): start
  or resume the cheque backfill after startup.

### Production Requirements

- Serve the gateway, and the frontend, only over HTTPS with TLS terminated
  at the gateway or a load balancer in front of it; plain HTTP only for
  local development.
- Set `GATEWAY_CORS_ALLOWED_ORIGINS` to the exact HTTPS origin of the
  deployed frontend; never a wildcard.
- Expose only the gateway; the services, `/internal/**`, and the databases
  must not be reachable from outside the private network.
- Configure the gateway's trusted proxies and forwarded headers for the
  real network, and add rate limiting (especially for login and
  registration).

## 13. Unresolved Decisions

The following must be researched or agreed before implementation:

- Authentication approach, token strategy, and identity provider (if any).
- Exact tenant model and tenant-isolation implementation.
- Database ownership, schema design, and migration strategy for each service.
- Bank data import format and future bank integration/API approach.
- Verified official source and update process for Sri Lankan bank holidays.
- Forecast estimation algorithm and its validation against historical data.
- Business rules for forecast treatment of bank holidays.
- Outbox publishing for bank-integration-service, alerting and a replay
  procedure for dead-lettered or long-pending events, and production broker
  setup (TLS, users and permissions per service, high availability).
- When forecasts switch from the payment API to the cheque projection, and
  how the projection is verified against payment-service's summary
  (milestone 3).
- How the backfill should be started and monitored in production (startup
  flag, an internal endpoint, or a scheduled job), and who reviews and
  resolves rejected items.
- Whether a backfill run should stop instead of continuing when items are
  rejected; it currently records and continues.
- Whether the internal cheque scan needs a stronger consistency model (for
  example a scan watermark or a database snapshot export) instead of the
  summary-and-rescan check, and whether the shared internal key should be
  replaced by per-service credentials or mTLS in production.
- Retention of `processed_events` rows (currently kept forever).
- Exact audit retention and access requirements.
- Company roles permitted through self-registration, the membership approval
  workflow, and whether to add an approval-pending membership status.
- Email verification and registration rate-limiting approach.
- How `PENDING` accounts become `ACTIVE`, and who may register companies.
- Refresh tokens, token revocation, and login rate limiting or lockout.
- How other services will validate access tokens (asymmetric signing keys,
  gateway validation, or another approach).
- The permission matrix for features not built yet (historical import
  approval, forecast, company settings) across services.
- Production-grade service-to-service authentication (for example mTLS) to
  replace the shared `IDENTITY_INTERNAL_API_SECRET`, and whether membership
  answers may be cached and for how long.
- Account-number policy: whether full account numbers are stored (encrypted)
  and how keys are managed; whether account numbers must be unique.
- Whether account types other than `CURRENT`, or more than one active account
  per company, are needed after the MVP, and how a current account is
  replaced or deactivated.
- Who may correct recorded sales, under what conditions, and how corrections
  are audited.
- The React UI still has a single `SYSTEM_ADMIN` role that represents the
  company administrator, and no `COMPANY_ADMIN` or platform-admin screens.
  Updating the frontend to the confirmed role architecture (section 4) is
  outstanding.
- How `PENDING` accounts are activated (email verification, or activation by
  an administrator), and how platform-level settings are managed.
- Self-service tenant onboarding, if any, instead of companies being created
  by a `SYSTEM_ADMIN`.
- The UI shows cheque states `Scheduled`, `Assumed paid`, `Cancelled`, and
  `Bounced`. The confirmed lifecycle has only `ISSUED` and `CANCELLED`:
  `Bounced` is not implemented, and "Assumed paid" is a display label for an
  `ISSUED` cheque past its clearing date. How overdue cheques are treated in
  the forecast, and whether bounced cheques are recorded, is still open.
- How cheques that need correction after their expected clearing date are
  handled; the UI and the API allow no change once that date has passed.
- Audit events for company creation and membership changes in
  identity-service (deferred; plan proposed, questions still open).
- Statement formats of other banks, including whether any bank uses `d/M`
  dates (the parser assumes `M/d/yyyy`, which is ambiguous for days 1-12).
- Whether a repeated statement file should be refused or need confirmation
  instead of being imported with all rows counted as duplicates.
- The time of day a statement closing balance applies to (currently end of
  the last transaction date in `Asia/Colombo`), and how statement balances
  rank against `BANK_API` or manual balances.
- Duplicate detection for statements without a running balance, and how
  bank corrections or reversals of imported rows are handled.
- Whether `ACCOUNTANT` may classify transactions, and whether automatic
  classification rules (`RULE`, `BANK_METADATA`) will be introduced.
- The forecasting method for sales inflows. The business direction mentions
  current-year actual collections and the previous year's corresponding
  weekly or daily pattern; the calculation, lookback, minimum history, and
  fallback when history is missing are not confirmed. The current
  `WEEKDAY_AVERAGE` strategy is a placeholder behind a replaceable interface.
- Whether a forecast may run from a stale balance (currently it runs and is
  labelled), and the stale threshold.
- Whether the horizon should start the day after the balance date (current)
  or on the current day, which matters when the balance is stale.
- Treatment of `ISSUED` cheques expected on or before the balance date that
  may not have cleared; they are currently not deducted and not marked paid.
- The Forecast vs Actual baseline-run rule (which saved run is compared for
  a period); the API currently takes the run from the client.
- Who may create forecast runs, and whether runs should also be created on a
  schedule or when source data changes (event consumers and projections).
- Whether forecasts should show confidence ranges; the UI demonstration
  shows low/high bands that the backend does not calculate.
- Production gateway hosting: TLS certificates, the deployed frontend
  origin, rate limits, and whether the gateway should also reject invalid
  tokens early (it would then need the token key or a public-key setup).
- Token lifetime handling in the browser: there are no refresh tokens, so a
  user signs in again after the access token expires.
- Whether daily sales may be corrected, whether they carry a note, and
  whether other users may see who recorded them (the API has no name).
- APIs for historical sales import, inviting or creating users, company
  settings, and listing companies for a platform administrator.
- The sample bank statement in the bank-integration test resources contains
  personal data (names, a phone number, deposit references) and is already
  in the Git history; whether to replace it with an anonymized fixture and
  clean the history.

## 14. Decision Log Maintenance

- Update this document when a business or architecture decision is confirmed.
- Keep unresolved questions visible until explicitly decided.
- Do not treat proposed solutions as confirmed decisions.
