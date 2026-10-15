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
  status, and version but no payee or notes. Publishing the outbox (to the
  forecast) is not implemented yet.

### Authorization and Dependencies

- Calls to identity-service and bank-integration-service finish before any
  database transaction starts. Both fail closed (503).
- Local setup: port 8083; database `flowledger_payment` in the `payment-db`
  container on port 5436; password from `PAYMENT_DB_PASSWORD`;
  `BANK_INTEGRATION_SERVICE_URL` (default `http://localhost:8082`).

### Known Limitations

- No outbox publisher yet, so the forecast does not receive cheque events.
- No audit-history read API.
- Listing is not paginated.
- The JWT validation, identity client, and authorization code is also
  duplicated here.

## 10. Unresolved Decisions

The following must be researched or agreed before implementation:

- Authentication approach, token strategy, and identity provider (if any).
- Exact tenant model and tenant-isolation implementation.
- Database ownership, schema design, and migration strategy for each service.
- Bank data import format and future bank integration/API approach.
- Verified official source and update process for Sri Lankan bank holidays.
- Forecast estimation algorithm and its validation against historical data.
- Business rules for forecast treatment of bank holidays.
- Reliable event publishing, event delivery guarantees, retries, and
  idempotency details.
- Exact audit retention and access requirements.
- Whether and when to introduce Spring Cloud Gateway, after verifying
  compatibility with the selected Spring Boot version.
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

## 11. Decision Log Maintenance

- Update this document when a business or architecture decision is confirmed.
- Keep unresolved questions visible until explicitly decided.
- Do not treat proposed solutions as confirmed decisions.
