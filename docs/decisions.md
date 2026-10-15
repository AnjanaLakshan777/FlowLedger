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

### Cheques

- Supported cheque statuses are `ISSUED` and `CANCELLED`.
- Forecast outflows include issued cheques using their expected clearing dates.
- Cancelled cheques are excluded from forecasts.
- Do not automatically mark overdue cheques as paid.
- Actual outflows include cheque payments actually debited by their actual
  bank transaction date.

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
- Public endpoints: health, login, company registration, and user
  registration. Every other endpoint requires a valid bearer token.
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
  only `allowed` and, when allowed, `companyRole`.
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

### Known Limitations

- Email verification and membership approval are not yet implemented.
- No flow activates `PENDING` accounts yet, so newly registered users cannot
  log in. For local testing, accounts must be activated directly in the
  database.
- Company and user registration remain public endpoints.
- There are no refresh tokens or token revocation. An access token stays
  valid until it expires.
- Login has no rate limiting or lockout after failed attempts.
- Company registration, user registration, and login do not yet write to
  `identity_audit_events`. Auditing these actions remains outstanding.

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
- Initial role policy for bank accounts (provisional, not a confirmed
  business rule):

  | Operation | Allowed roles |
  |---|---|
  | List bank accounts | `COMPANY_ADMIN`, `FINANCE_CONTROLLER`, `ACCOUNTANT` |
  | Read a bank account | `COMPANY_ADMIN`, `FINANCE_CONTROLLER`, `ACCOUNTANT` |
  | Create a bank account | `COMPANY_ADMIN`, `FINANCE_CONTROLLER` |

  `CASHIER` has no access to bank accounts.
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

### Known Limitations

- Every company-scoped request makes a synchronous call to identity-service,
  so bank account endpoints are unavailable while identity-service is down.
  There is no caching of membership answers.
- The role policy is enforced in bank-integration-service; there is no
  shared permission model across services yet.
- Bank account changes do not yet write to `bank_integration_audit_events`,
  and there is no outbox.

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
  company (confirmed rule, section 4).
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

## 9. Unresolved Decisions

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
- Confirmation of the provisional bank account role policy, and the wider
  role-permission matrix across services.
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
- The UI permission matrix (`frontend/src/lib/permissions.ts`) uses a
  `SYSTEM_ADMIN` role, while identity-service's company roles use
  `COMPANY_ADMIN`; how the two map, and whether a separate platform-level
  administrator exists, is not decided.
- The UI matrix gives "Configure bank integration" only to `SYSTEM_ADMIN`,
  while bank-integration-service currently lets `COMPANY_ADMIN` and
  `FINANCE_CONTROLLER` create bank accounts. Whether creating a bank account
  counts as configuring bank integration needs to be decided.

## 10. Decision Log Maintenance

- Update this document when a business or architecture decision is confirmed.
- Keep unresolved questions visible until explicitly decided.
- Do not treat proposed solutions as confirmed decisions.
