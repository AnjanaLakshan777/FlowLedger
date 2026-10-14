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
  holding the secret could also issue tokens, so it must not be copied to
  other services. If other services need to validate tokens, use asymmetric
  signing (for example RS256 or ES256 with published public keys) or validate
  at the API gateway.

### Known Limitations

- Email verification, membership approval, and backend-enforced company RBAC
  are not yet implemented.
- No flow activates `PENDING` accounts yet, so newly registered users cannot
  log in. For local testing, accounts must be activated directly in the
  database.
- Company and user registration remain public endpoints.
- There are no refresh tokens or token revocation. An access token stays
  valid until it expires.
- Login has no rate limiting or lockout after failed attempts.
- Company registration, user registration, and login do not yet write to
  `identity_audit_events`. Auditing these actions remains outstanding.

## 7. Unresolved Decisions

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

## 8. Decision Log Maintenance

- Update this document when a business or architecture decision is confirmed.
- Keep unresolved questions visible until explicitly decided.
- Do not treat proposed solutions as confirmed decisions.
