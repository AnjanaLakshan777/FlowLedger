# FlowLedger — Final Database Schema v1

**Status:** Logical schema v1 for review before creating executable SQL migrations.  
**Purpose:** Define MVP tables, columns, PostgreSQL data types, primary/foreign keys, constraints and indexes.  
**Database:** PostgreSQL. Each service owns its own database/schema; never create cross-service foreign keys.  
**Conventions:** UUID identifiers; money `NUMERIC(19,2)`; dates `DATE`; timestamps `TIMESTAMPTZ`; JSON payloads `JSONB`; UI timezone `Asia/Colombo`. Store timestamps consistently (normally UTC) and render in `Asia/Colombo`.

## 1. Confirmed product rules reflected here

- Forecast horizon: **84 days (12 weeks)**; cash shortage when projected closing balance is below **LKR 0**.
- MVP uses one company Current Bank Account. Card settlement is not a second company current account.
- All Cash Sales and Card Sales are assumed to be deposited/settled into that company bank account.
- Forecast future inflows from **historical actual bank cash-flow patterns**. Use classified historical bank transactions rather than a separate Forecast copy of Sales history. Exact lookback/seasonality algorithm remains configurable.
- Sales are still recorded in Cash Management, with original `sale_date` and actual `recorded_at`; they are not the source of truth for the date cash arrived in the bank.
- Forecast-vs-Actual actual inflows: bank credits categorized `CASH_SALES_DEPOSIT` or `CARD_SALES_SETTLEMENT`. Actual outflows: bank debits categorized `CHEQUE_PAYMENT`. Other bank transactions are excluded from those comparison totals.
- Cheque states are only `ISSUED` and `CANCELLED`. Do not assume a cheque is paid just because its expected clearing date has passed. Detailed overdue-cheque handling is deferred until Bank Reconciliation is designed.
- Full Bank Reconciliation/matching is out of scope. A bank transaction may be categorized by type but is not linked to an individual Sale or Cheque in this schema.
- Calendar stores Sri Lankan holiday data, including bank-holiday markers and source/verification metadata. Calendar data alone does not automatically shift forecast dates until a separate business rule is approved.
- Notification Service and Gateway business tables are not required for MVP.

## 2. MVP table inventory (28 tables)

| Service | Tables | Count |
|---|---|---:|
| Identity | `companies`, `users`, `company_memberships`, `user_sessions`, `identity_audit_events` | 5 |
| Cash Management | `daily_sales`, `sales_import_batches`, `sales_import_rows`, `cash_management_audit_events`, `outbox_events` | 5 |
| Payment | `cheques`, `payment_audit_events`, `outbox_events` | 3 |
| Bank Integration | `bank_accounts`, `bank_statement_import_batches`, `bank_balance_snapshots`, `bank_transactions`, `bank_integration_audit_events`, `outbox_events` | 6 |
| Forecast + Calendar | `forecast_cheques`, `forecast_bank_balance_projection`, `forecast_bank_transaction_projection`, `forecast_model_configs`, `forecast_runs`, `forecast_daily_results`, `forecast_run_cheques`, `processed_events`, `calendar_holidays` | 9 |
| **Total** | | **28** |

`outbox_events` is a service-local table: one separate copy in each service listed. It is not a shared cross-service table.

---

# 3. Identity Service

## 3.1 `companies`

| Column | Type | Rules / purpose |
|---|---|---|
| `id` | UUID | PK, default `gen_random_uuid()` |
| `name` | VARCHAR(200) | NOT NULL |
| `registration_number` | VARCHAR(80) | NULL; optional business registration identifier |
| `default_currency` | CHAR(3) | NOT NULL, default `'LKR'` |
| `timezone` | VARCHAR(80) | NOT NULL, default `'Asia/Colombo'` |
| `status` | VARCHAR(20) | NOT NULL; CHECK in `ACTIVE`, `SUSPENDED`, `CLOSED` |
| `created_at` | TIMESTAMPTZ | NOT NULL, default `now()` |
| `updated_at` | TIMESTAMPTZ | NOT NULL, default `now()` |

**Indexes/constraints:** index on `(status, name)` if company directory search is needed. Registration number uniqueness policy depends on whether it is mandatory; do not enforce unique when NULL.

## 3.2 `users`

| Column | Type | Rules / purpose |
|---|---|---|
| `id` | UUID | PK, default `gen_random_uuid()` |
| `email` | VARCHAR(320) | NOT NULL; store normalized lowercase value |
| `full_name` | VARCHAR(160) | NOT NULL |
| `password_hash` | VARCHAR(255) | NOT NULL; Argon2id/bcrypt hash only, never plaintext |
| `account_status` | VARCHAR(20) | NOT NULL; CHECK in `ACTIVE`, `LOCKED`, `DISABLED`, `PENDING` |
| `platform_role` | VARCHAR(30) | NOT NULL, default `USER`; platform-only role, not company role |
| `created_at` | TIMESTAMPTZ | NOT NULL, default `now()` |
| `updated_at` | TIMESTAMPTZ | NOT NULL, default `now()` |
| `last_login_at` | TIMESTAMPTZ | NULL |

**Indexes/constraints:** unique index on `lower(email)` (or store a canonical lowercase email and uniquely index it); index on `account_status`. Exact platform roles remain to be agreed; do not conflate platform administrator and company membership roles.

## 3.3 `company_memberships`

| Column | Type | Rules / purpose |
|---|---|---|
| `id` | UUID | PK, default `gen_random_uuid()` |
| `company_id` | UUID | NOT NULL, FK → `companies.id` |
| `user_id` | UUID | NOT NULL, FK → `users.id` |
| `company_role` | VARCHAR(30) | NOT NULL; CHECK in `COMPANY_ADMIN`, `FINANCE_CONTROLLER`, `ACCOUNTANT`, `CASHIER` |
| `status` | VARCHAR(20) | NOT NULL; CHECK in `ACTIVE`, `INVITED`, `SUSPENDED` |
| `invited_by` | UUID | NULL, FK → `users.id` |
| `created_at` | TIMESTAMPTZ | NOT NULL, default `now()` |
| `updated_at` | TIMESTAMPTZ | NOT NULL, default `now()` |

**Indexes/constraints:** UNIQUE `(company_id, user_id)`; index `(user_id, status)`. Detailed role-permission matrix is an application/security decision and remains to be confirmed.

## 3.4 `user_sessions`

| Column | Type | Rules / purpose |
|---|---|---|
| `id` | UUID | PK, default `gen_random_uuid()` |
| `user_id` | UUID | NOT NULL, FK → `users.id` |
| `refresh_token_hash` | VARCHAR(255) | NOT NULL, unique; never store refresh token plaintext |
| `created_at` | TIMESTAMPTZ | NOT NULL, default `now()` |
| `expires_at` | TIMESTAMPTZ | NOT NULL |
| `revoked_at` | TIMESTAMPTZ | NULL |
| `device_label` | VARCHAR(160) | NULL |
| `ip_address` | INET | NULL; retain only if required by security policy |

**Indexes/constraints:** index `(user_id, expires_at)`; index `(expires_at)` for cleanup. Enforce `expires_at > created_at`.

## 3.5 `identity_audit_events`

| Column | Type | Rules / purpose |
|---|---|---|
| `id` | UUID | PK, default `gen_random_uuid()` |
| `actor_user_id` | UUID | NULL, FK → `users.id`; NULL for system/unknown actor |
| `action` | VARCHAR(80) | NOT NULL |
| `target_type` | VARCHAR(80) | NOT NULL |
| `target_id` | UUID | NULL; ID of target entity |
| `occurred_at` | TIMESTAMPTZ | NOT NULL, default `now()` |
| `ip_address` | INET | NULL |
| `details` | JSONB | NOT NULL, default `{}` |

**Indexes/constraints:** `(occurred_at DESC)`, `(actor_user_id, occurred_at DESC)`, `(target_type, target_id)`. Append-only for normal application roles.

---

# 4. Cash Management Service

## 4.1 `daily_sales`

| Column | Type | Rules / purpose |
|---|---|---|
| `id` | UUID | PK, default `gen_random_uuid()` |
| `company_id` | UUID | NOT NULL; tenant ID from authenticated context; no cross-service FK |
| `sale_date` | DATE | NOT NULL; original business Sales date |
| `cash_sales_amount` | NUMERIC(19,2) | NOT NULL, default `0`; CHECK `>= 0` |
| `card_sales_amount` | NUMERIC(19,2) | NOT NULL, default `0`; CHECK `>= 0` |
| `entry_source` | VARCHAR(20) | NOT NULL; CHECK in `UI`, `HISTORICAL_IMPORT` |
| `import_batch_id` | UUID | NULL, FK → `sales_import_batches.id` |
| `recorded_by` | UUID | NOT NULL; user ID only, no cross-service FK |
| `recorded_at` | TIMESTAMPTZ | NOT NULL, default `now()`; actual entry timestamp |
| `updated_by` | UUID | NULL; user ID only |
| `updated_at` | TIMESTAMPTZ | NULL |
| `version` | INTEGER | NOT NULL, default `1`, CHECK `> 0`; optimistic locking |

**Indexes/constraints:** UNIQUE `(company_id, sale_date)`; UNIQUE `(company_id, id)` for tenant-safe composite references; composite FK `(company_id, import_batch_id)` → `sales_import_batches(company_id, id)` when `import_batch_id` is not NULL; index `(company_id, sale_date DESC)`. Require `cash_sales_amount + card_sales_amount > 0` for a saved Sales record. Missing date is not automatically zero Sales. Create the row only after the related money has reached the bank as per current product rule, but keep `sale_date` as the actual Sales date.

## 4.2 `sales_import_batches`

| Column | Type | Rules / purpose |
|---|---|---|
| `id` | UUID | PK, default `gen_random_uuid()` |
| `company_id` | UUID | NOT NULL; no cross-service FK |
| `original_filename` | VARCHAR(255) | NOT NULL |
| `file_sha256` | CHAR(64) | NULL; normalized SHA-256 hex digest |
| `storage_key` | VARCHAR(500) | NULL; object-storage path if original file is retained |
| `status` | VARCHAR(24) | NOT NULL; CHECK in `UPLOADED`, `VALIDATING`, `READY_FOR_APPROVAL`, `APPROVED`, `REJECTED`, `FAILED` |
| `period_start` | DATE | NULL |
| `period_end` | DATE | NULL |
| `row_count` | INTEGER | NOT NULL, default `0`, CHECK `>= 0` |
| `valid_row_count` | INTEGER | NOT NULL, default `0`, CHECK `>= 0` |
| `invalid_row_count` | INTEGER | NOT NULL, default `0`, CHECK `>= 0` |
| `duplicate_row_count` | INTEGER | NOT NULL, default `0`, CHECK `>= 0` |
| `uploaded_by` | UUID | NOT NULL; user ID only |
| `uploaded_at` | TIMESTAMPTZ | NOT NULL, default `now()` |
| `approved_by` | UUID | NULL; user ID only |
| `approved_at` | TIMESTAMPTZ | NULL |
| `rejection_reason` | TEXT | NULL |

**Indexes/constraints:** UNIQUE `(company_id, id)` for tenant-safe references; `(company_id, uploaded_at DESC)`, `(company_id, status)`. If both period dates are present, require `period_end >= period_start`. Recheck duplicates against both batch rows and `daily_sales` within the approval transaction. Exact duplicate-file policy (block versus warning) remains open.

## 4.3 `sales_import_rows`

| Column | Type | Rules / purpose |
|---|---|---|
| `id` | UUID | PK, default `gen_random_uuid()` |
| `batch_id` | UUID | NOT NULL, FK → `sales_import_batches.id` ON DELETE RESTRICT |
| `row_number` | INTEGER | NOT NULL, CHECK `> 0` |
| `sale_date` | DATE | NULL until parsed/validated |
| `cash_sales_amount` | NUMERIC(19,2) | NULL until parsed; if present, CHECK `>= 0` |
| `card_sales_amount` | NUMERIC(19,2) | NULL until parsed; if present, CHECK `>= 0` |
| `validation_status` | VARCHAR(20) | NOT NULL; CHECK in `PENDING`, `VALID`, `INVALID`, `DUPLICATE` |
| `duplicate_date` | BOOLEAN | NOT NULL, default `false` |
| `validation_errors` | JSONB | NOT NULL, default `[]`; array of validation issue objects |
| `raw_row` | JSONB | NULL; parsed source values for traceability |

**Indexes/constraints:** UNIQUE `(batch_id, row_number)`; index `(batch_id, validation_status)`; index `(batch_id, sale_date)`. Do not make `sale_date` unique here—duplicate rows need to remain visible for correction. Staging rows are retained after approval.

## 4.4 `cash_management_audit_events`

| Column | Type | Rules / purpose |
|---|---|---|
| `id` | UUID | PK, default `gen_random_uuid()` |
| `company_id` | UUID | NOT NULL; no cross-service FK |
| `actor_type` | VARCHAR(20) | NOT NULL; CHECK in `USER`, `SYSTEM` |
| `actor_user_id` | UUID | NULL; user ID only |
| `action` | VARCHAR(80) | NOT NULL |
| `entity_type` | VARCHAR(80) | NOT NULL |
| `entity_id` | UUID | NOT NULL |
| `occurred_at` | TIMESTAMPTZ | NOT NULL, default `now()` |
| `reason` | TEXT | NULL |
| `before_data` | JSONB | NULL |
| `after_data` | JSONB | NULL |
| `correlation_id` | UUID | NULL; ties related requests/events across services |

**Indexes/constraints:** `(company_id, occurred_at DESC)`, `(entity_type, entity_id, occurred_at DESC)`, `(correlation_id)`. Write in the same transaction as the business change. Append-only for normal roles.

## 4.5 `outbox_events` (Cash Management database)

| Column | Type | Rules / purpose |
|---|---|---|
| `id` | UUID | PK, default `gen_random_uuid()`; event ID |
| `aggregate_type` | VARCHAR(80) | NOT NULL |
| `aggregate_id` | UUID | NOT NULL |
| `event_type` | VARCHAR(120) | NOT NULL |
| `schema_version` | SMALLINT | NOT NULL, default `1`, CHECK `> 0` |
| `payload` | JSONB | NOT NULL |
| `created_at` | TIMESTAMPTZ | NOT NULL, default `now()` |
| `published_at` | TIMESTAMPTZ | NULL until broker confirms publish |
| `attempt_count` | INTEGER | NOT NULL, default `0`, CHECK `>= 0` |
| `next_attempt_at` | TIMESTAMPTZ | NULL |
| `last_error` | TEXT | NULL; do not store secrets or sensitive bank data |

**Indexes/constraints:** partial index on `(created_at)` for rows where `published_at IS NULL`; index `(aggregate_type, aggregate_id, created_at)`. Save the event in the same DB transaction as the Sales change; retry publish failures.

---

# 5. Payment Service

## 5.1 `cheques`

| Column | Type | Rules / purpose |
|---|---|---|
| `id` | UUID | PK, default `gen_random_uuid()` |
| `company_id` | UUID | NOT NULL; no cross-service FK |
| `bank_account_id` | UUID | NOT NULL; Bank Integration ID only, no cross-service FK |
| `cheque_number` | VARCHAR(40) | NOT NULL; preserve leading zeros |
| `payee_name` | VARCHAR(200) | NOT NULL |
| `amount` | NUMERIC(19,2) | NOT NULL, CHECK `> 0` |
| `issue_date` | DATE | NOT NULL |
| `expected_clearing_date` | DATE | NOT NULL |
| `status` | VARCHAR(16) | NOT NULL; CHECK in `ISSUED`, `CANCELLED` |
| `notes` | TEXT | NULL |
| `created_by` | UUID | NOT NULL; user ID only |
| `created_at` | TIMESTAMPTZ | NOT NULL, default `now()` |
| `updated_by` | UUID | NULL; user ID only |
| `updated_at` | TIMESTAMPTZ | NULL |
| `version` | INTEGER | NOT NULL, default `1`, CHECK `> 0` |

**Indexes/constraints:** UNIQUE `(company_id, bank_account_id, cheque_number)`; index `(company_id, status, expected_clearing_date)`; index `(company_id, issue_date DESC)`. Require `expected_clearing_date >= issue_date` unless a confirmed business case permits back-dated clearing dates. Do not mark a cheque paid automatically after the expected date.

## 5.2 `payment_audit_events`

| Column | Type | Rules / purpose |
|---|---|---|
| `id` | UUID | PK, default `gen_random_uuid()` |
| `company_id` | UUID | NOT NULL; no cross-service FK |
| `actor_user_id` | UUID | NULL; user ID only |
| `action` | VARCHAR(80) | NOT NULL |
| `cheque_id` | UUID | NOT NULL; cheque ID in this service |
| `occurred_at` | TIMESTAMPTZ | NOT NULL, default `now()` |
| `reason` | TEXT | NULL; required by application for cancellation |
| `before_data` | JSONB | NULL |
| `after_data` | JSONB | NULL |

**Indexes/constraints:** `(company_id, occurred_at DESC)`, `(cheque_id, occurred_at DESC)`. Normal users cannot update/delete audit history.

## 5.3 `outbox_events` (Payment database)

Use the same columns and indexes defined for Cash Management's `outbox_events`, but in the Payment database. Events include `ChequeIssued`, `ChequeUpdated`, and `ChequeCancelled`. Publish only after the cheque transaction commits, using the transactional outbox worker.

---

# 6. Bank Integration Service

## 6.1 `bank_accounts`

| Column | Type | Rules / purpose |
|---|---|---|
| `id` | UUID | PK, default `gen_random_uuid()` |
| `company_id` | UUID | NOT NULL; no cross-service FK |
| `bank_name` | VARCHAR(160) | NOT NULL |
| `account_name` | VARCHAR(160) | NOT NULL |
| `account_type` | VARCHAR(24) | NOT NULL; MVP uses `CURRENT` |
| `account_number_encrypted` | BYTEA | NULL; encrypted only if full number must be stored |
| `account_number_last4` | CHAR(4) | NULL; masked display aid |
| `currency` | CHAR(3) | NOT NULL, default `'LKR'` |
| `integration_mode` | VARCHAR(24) | NOT NULL; CHECK in `STATEMENT_IMPORT`, `BANK_API`, `SIMULATOR`, `MANUAL` |
| `status` | VARCHAR(20) | NOT NULL; CHECK in `ACTIVE`, `INACTIVE` |
| `created_at` | TIMESTAMPTZ | NOT NULL, default `now()` |
| `updated_at` | TIMESTAMPTZ | NOT NULL, default `now()` |

**Indexes/constraints:** Add UNIQUE `(company_id, id)` so other tables can use tenant-safe composite foreign keys; also index `(company_id, status)`. Enforce the MVP one-active-current-account rule with a partial unique index on `company_id` where `account_type='CURRENT' AND status='ACTIVE'` if that rule is to remain strict.

## 6.2 `bank_statement_import_batches`

| Column | Type | Rules / purpose |
|---|---|---|
| `id` | UUID | PK, default `gen_random_uuid()` |
| `company_id` | UUID | NOT NULL; no cross-service FK |
| `bank_account_id` | UUID | NOT NULL; composite FK `(company_id, bank_account_id)` → `bank_accounts(company_id, id)` |
| `filename` | VARCHAR(255) | NOT NULL |
| `file_sha256` | CHAR(64) | NOT NULL; file hash for duplicate detection |
| `status` | VARCHAR(24) | NOT NULL; CHECK in `UPLOADED`, `VALIDATING`, `IMPORTED`, `FAILED` |
| `period_start` | DATE | NULL |
| `period_end` | DATE | NULL |
| `row_count` | INTEGER | NOT NULL, default `0`, CHECK `>= 0` |
| `imported_by` | UUID | NOT NULL; user ID only |
| `imported_at` | TIMESTAMPTZ | NOT NULL, default `now()` |
| `error_summary` | TEXT | NULL |

**Indexes/constraints:** UNIQUE `(company_id, id)` for downstream tenant-safe references; composite FK `(company_id, bank_account_id)` → `bank_accounts(company_id, id)`. Index `(company_id, imported_at DESC)`, `(bank_account_id, period_start, period_end)`, `(file_sha256)`. Duplicate-file handling policy remains open; do not reject solely on hash until that policy is confirmed.

## 6.3 `bank_balance_snapshots`

| Column | Type | Rules / purpose |
|---|---|---|
| `id` | UUID | PK, default `gen_random_uuid()` |
| `company_id` | UUID | NOT NULL; no cross-service FK |
| `bank_account_id` | UUID | NOT NULL; composite FK `(company_id, bank_account_id)` → `bank_accounts(company_id, id)` |
| `balance_amount` | NUMERIC(19,2) | NOT NULL; can be negative if bank overdraft is supported |
| `currency` | CHAR(3) | NOT NULL, default `'LKR'` |
| `balance_as_of` | TIMESTAMPTZ | NOT NULL; time the balance actually applies to |
| `source_type` | VARCHAR(24) | NOT NULL; CHECK in `STATEMENT_IMPORT`, `BANK_API`, `SIMULATOR`, `MANUAL` |
| `source_import_batch_id` | UUID | NULL; composite FK `(company_id, source_import_batch_id)` → `bank_statement_import_batches(company_id, id)` |
| `created_at` | TIMESTAMPTZ | NOT NULL, default `now()` |

**Indexes/constraints:** `(company_id, bank_account_id, balance_as_of DESC)`. Enforce the composite FKs `(company_id, bank_account_id)` → `bank_accounts(company_id, id)` and `(company_id, source_import_batch_id)` → `bank_statement_import_batches(company_id, id)` when the import batch is not NULL. Forecast must use the selected snapshot timestamp and make stale data visible; never substitute a missing balance with zero.

## 6.4 `bank_transactions`

| Column | Type | Rules / purpose |
|---|---|---|
| `id` | UUID | PK, default `gen_random_uuid()` |
| `company_id` | UUID | NOT NULL; no cross-service FK |
| `bank_account_id` | UUID | NOT NULL; composite FK `(company_id, bank_account_id)` → `bank_accounts(company_id, id)` |
| `external_transaction_id` | VARCHAR(160) | NULL; bank-provided identifier, if available |
| `deduplication_fingerprint` | CHAR(64) | NOT NULL; normalized digest generated by importer/adapter |
| `transaction_date` | DATE | NOT NULL; bank transaction date for reports |
| `value_date` | DATE | NULL; bank value date if statement provides it |
| `direction` | VARCHAR(8) | NOT NULL; CHECK in `CREDIT`, `DEBIT` |
| `amount` | NUMERIC(19,2) | NOT NULL, CHECK `> 0`; direction represents the sign |
| `currency` | CHAR(3) | NOT NULL, default `'LKR'` |
| `description` | TEXT | NULL; sanitize before display/logging |
| `category` | VARCHAR(32) | NOT NULL, default `UNCLASSIFIED`; CHECK in `CASH_SALES_DEPOSIT`, `CARD_SALES_SETTLEMENT`, `CHEQUE_PAYMENT`, `OTHER`, `UNCLASSIFIED` |
| `classification_method` | VARCHAR(24) | NOT NULL, default `UNKNOWN`; CHECK in `BANK_METADATA`, `RULE`, `MANUAL`, `UNKNOWN` |
| `classified_by` | UUID | NULL; user ID only |
| `classified_at` | TIMESTAMPTZ | NULL |
| `source_import_batch_id` | UUID | NULL; composite FK `(company_id, source_import_batch_id)` → `bank_statement_import_batches(company_id, id)` |
| `created_at` | TIMESTAMPTZ | NOT NULL, default `now()` |
| `updated_at` | TIMESTAMPTZ | NOT NULL, default `now()` |

**Indexes/constraints:** composite FKs `(company_id, bank_account_id)` → `bank_accounts(company_id, id)` and `(company_id, source_import_batch_id)` → `bank_statement_import_batches(company_id, id)` when source batch is not NULL; UNIQUE `(bank_account_id, deduplication_fingerprint)`; partial unique index `(bank_account_id, external_transaction_id)` where external ID is not NULL; `(company_id, transaction_date DESC)`, `(bank_account_id, transaction_date DESC)`, `(company_id, category, transaction_date)`. Fingerprint generation must preserve legitimately repeated transactions and should prefer bank-provided references where possible. If the bank supplies no reliable type, do not guess—leave `UNCLASSIFIED` and flag Forecast-vs-Actual data as incomplete. This is category classification only, not Sale/Cheque matching.

## 6.5 `bank_integration_audit_events`

| Column | Type | Rules / purpose |
|---|---|---|
| `id` | UUID | PK, default `gen_random_uuid()` |
| `company_id` | UUID | NOT NULL; no cross-service FK |
| `actor_type` | VARCHAR(20) | NOT NULL; CHECK in `USER`, `SYSTEM` |
| `actor_user_id` | UUID | NULL; user ID only |
| `action` | VARCHAR(80) | NOT NULL |
| `entity_type` | VARCHAR(80) | NOT NULL |
| `entity_id` | UUID | NOT NULL |
| `occurred_at` | TIMESTAMPTZ | NOT NULL, default `now()` |
| `reason` | TEXT | NULL |
| `before_data` | JSONB | NULL |
| `after_data` | JSONB | NULL |

**Indexes/constraints:** `(company_id, occurred_at DESC)`, `(entity_type, entity_id, occurred_at DESC)`. Append-only to normal users.

## 6.6 `outbox_events` (Bank Integration database)

Use the same service-local outbox columns and indexes defined in section 4.5. Events include balance snapshot updates, new transactions and changes to transaction category. Forecast receives events; it never queries the Bank Integration database directly.

---

# 7. Forecast Service and Calendar

## 7.1 `forecast_cheques`

| Column | Type | Rules / purpose |
|---|---|---|
| `cheque_id` | UUID | PK; source ID from Payment Service, no cross-service FK |
| `company_id` | UUID | NOT NULL |
| `cheque_number` | VARCHAR(40) | NOT NULL; preserve leading zeros |
| `amount` | NUMERIC(19,2) | NOT NULL, CHECK `> 0` |
| `issue_date` | DATE | NOT NULL |
| `expected_clearing_date` | DATE | NOT NULL |
| `status` | VARCHAR(16) | NOT NULL; CHECK in `ISSUED`, `CANCELLED` |
| `source_version` | INTEGER | NOT NULL, CHECK `> 0` |
| `source_updated_at` | TIMESTAMPTZ | NOT NULL |
| `last_event_id` | UUID | NOT NULL; last applied source event |

**Indexes/constraints:** `(company_id, status, expected_clearing_date)`. Consume each source event idempotently and ignore stale source versions. No rule here treats overdue cheques as paid.

## 7.2 `forecast_bank_balance_projection`

| Column | Type | Rules / purpose |
|---|---|---|
| `bank_account_id` | UUID | PK; source ID from Bank Integration, no cross-service FK |
| `company_id` | UUID | NOT NULL |
| `balance_amount` | NUMERIC(19,2) | NOT NULL |
| `currency` | CHAR(3) | NOT NULL, default `'LKR'` |
| `balance_as_of` | TIMESTAMPTZ | NOT NULL |
| `source_updated_at` | TIMESTAMPTZ | NOT NULL |
| `last_event_id` | UUID | NOT NULL |

**Indexes/constraints:** `(company_id, balance_as_of DESC)`. One current projection row per source bank account; older snapshots remain in Bank Integration. The forecast UI must show the as-of time and stale status.

## 7.3 `forecast_bank_transaction_projection`

| Column | Type | Rules / purpose |
|---|---|---|
| `bank_transaction_id` | UUID | PK; source ID from Bank Integration, no cross-service FK |
| `company_id` | UUID | NOT NULL |
| `bank_account_id` | UUID | NOT NULL; source ID only |
| `transaction_date` | DATE | NOT NULL |
| `direction` | VARCHAR(8) | NOT NULL; CHECK in `CREDIT`, `DEBIT` |
| `amount` | NUMERIC(19,2) | NOT NULL, CHECK `> 0` |
| `currency` | CHAR(3) | NOT NULL, default `'LKR'` |
| `category` | VARCHAR(32) | NOT NULL; same allowed values as Bank Integration |
| `source_version` | INTEGER | NOT NULL, default `1`, CHECK `> 0` |
| `source_updated_at` | TIMESTAMPTZ | NOT NULL |
| `last_event_id` | UUID | NOT NULL |

**Indexes/constraints:** `(company_id, transaction_date)`, `(company_id, category, transaction_date)`, `(bank_account_id, transaction_date)`. Use only classified eligible categories in Forecast vs Actual. For the inflow forecasting method, learn from historical transactions categorized as `CASH_SALES_DEPOSIT` and `CARD_SALES_SETTLEMENT`; future amounts are estimates, not imported bank transactions.

## 7.4 `forecast_model_configs`

| Column | Type | Rules / purpose |
|---|---|---|
| `id` | UUID | PK, default `gen_random_uuid()` |
| `company_id` | UUID | NOT NULL |
| `version` | INTEGER | NOT NULL, CHECK `> 0` |
| `is_active` | BOOLEAN | NOT NULL, default `false` |
| `horizon_days` | SMALLINT | NOT NULL, CHECK `= 84` for MVP |
| `minimum_balance` | NUMERIC(19,2) | NOT NULL, default `0.00`; MVP uses LKR 0 shortage threshold |
| `method_code` | VARCHAR(80) | NOT NULL; MVP `HISTORICAL_BANK_CASH_FLOW_PATTERN` |
| `parameters` | JSONB | NOT NULL, default `{}`; model/lookback/weekday/seasonality configuration |
| `created_by` | UUID | NOT NULL; user ID only |
| `created_at` | TIMESTAMPTZ | NOT NULL, default `now()` |

**Indexes/constraints:** UNIQUE `(company_id, id)` for tenant-safe references; UNIQUE `(company_id, version)`; partial unique index on `company_id` where `is_active=true`; index `(company_id, created_at DESC)`. Exact lookback window and seasonality settings remain configuration/business decisions, not hardcoded assumptions in this schema.

## 7.5 `forecast_runs`

| Column | Type | Rules / purpose |
|---|---|---|
| `id` | UUID | PK, default `gen_random_uuid()` |
| `company_id` | UUID | NOT NULL |
| `config_id` | UUID | NOT NULL, FK → `forecast_model_configs.id` |
| `run_status` | VARCHAR(16) | NOT NULL; CHECK in `QUEUED`, `RUNNING`, `SUCCEEDED`, `FAILED` |
| `trigger_type` | VARCHAR(16) | NOT NULL; CHECK in `MANUAL`, `SCHEDULED`, `DATA_CHANGE` |
| `as_of_at` | TIMESTAMPTZ | NOT NULL; inputs' cutoff time for reproducibility |
| `horizon_start_date` | DATE | NOT NULL |
| `horizon_end_date` | DATE | NOT NULL |
| `opening_balance_amount` | NUMERIC(19,2) | NOT NULL |
| `opening_balance_as_of` | TIMESTAMPTZ | NOT NULL; balance snapshot's as-of time |
| `model_version` | VARCHAR(40) | NOT NULL; code/model version |
| `config_snapshot` | JSONB | NOT NULL; immutable copy of effective config |
| `input_snapshot_hash` | CHAR(64) | NULL; digest of relevant inputs for traceability |
| `triggered_by` | UUID | NULL; user ID only, NULL for system |
| `created_at` | TIMESTAMPTZ | NOT NULL, default `now()` |
| `completed_at` | TIMESTAMPTZ | NULL |
| `error_summary` | TEXT | NULL; sanitized, no secrets |

**Indexes/constraints:** composite FK `(company_id, config_id)` → `forecast_model_configs(company_id, id)`; `(company_id, created_at DESC)`, `(company_id, horizon_start_date, horizon_end_date)`, `(run_status, created_at)`. Require `horizon_end_date >= horizon_start_date`; successful run contains exactly 84 daily result rows. A saved run is immutable; later changes produce a new run. The baseline-run selection rule for Forecast vs Actual still needs product confirmation.

## 7.6 `forecast_daily_results`

**Composite primary key:** `(forecast_run_id, forecast_date)`.

| Column | Type | Rules / purpose |
|---|---|---|
| `forecast_run_id` | UUID | NOT NULL, FK → `forecast_runs.id` ON DELETE RESTRICT |
| `forecast_date` | DATE | NOT NULL |
| `expected_cash_sales_deposits_amount` | NUMERIC(19,2) | NOT NULL, default `0`, CHECK `>= 0`; modelled from historical bank pattern |
| `expected_card_sales_settlements_amount` | NUMERIC(19,2) | NOT NULL, default `0`, CHECK `>= 0` |
| `expected_sales_inflows_amount` | NUMERIC(19,2) | NOT NULL, default `0`, CHECK `>= 0`; sum of the two forecast inflows |
| `expected_cheque_outflows_amount` | NUMERIC(19,2) | NOT NULL, default `0`, CHECK `>= 0` |
| `opening_balance_amount` | NUMERIC(19,2) | NOT NULL |
| `closing_balance_amount` | NUMERIC(19,2) | NOT NULL |
| `is_cash_shortage` | BOOLEAN | NOT NULL; true when closing balance `< 0` |

**Indexes/constraints:** PK `(forecast_run_id, forecast_date)`; index `(forecast_date)` for calendar/report queries. Enforce `expected_sales_inflows_amount = expected_cash_sales_deposits_amount + expected_card_sales_settlements_amount` in calculation/application logic or a CHECK constraint. For sequential dates, day 1 opening is the selected confirmed bank balance; each later opening equals prior day's closing. Expected outflows are based on eligible `ISSUED` cheques using `expected_clearing_date`, subject to the overdue-cheque rule that remains open. This table contains forecasts only, never actual bank transaction values.

## 7.7 `forecast_run_cheques`

| Column | Type | Rules / purpose |
|---|---|---|
| `forecast_run_id` | UUID | NOT NULL, FK → `forecast_runs.id` ON DELETE RESTRICT |
| `cheque_id` | UUID | NOT NULL; Payment Service source ID, no cross-service FK |
| `cheque_number` | VARCHAR(40) | NOT NULL; snapshot value |
| `amount` | NUMERIC(19,2) | NOT NULL, CHECK `> 0`; snapshot value |
| `expected_clearing_date` | DATE | NOT NULL; snapshot value |
| `status_at_run` | VARCHAR(16) | NOT NULL; CHECK in `ISSUED`, `CANCELLED` |

**Primary key:** `(forecast_run_id, cheque_id)`. Index `(cheque_id)`. Store the cheque values included in that run so later source changes do not rewrite history. Only cheques included in the model need to be stored; cancelled cheques should not contribute to outflows.

## 7.8 `processed_events`

| Column | Type | Rules / purpose |
|---|---|---|
| `event_id` | UUID | PK; source outbox/event ID |
| `source_service` | VARCHAR(80) | NOT NULL |
| `event_type` | VARCHAR(120) | NOT NULL |
| `processed_at` | TIMESTAMPTZ | NOT NULL, default `now()` |
| `result_summary` | JSONB | NULL; sanitized processing result |

**Indexes/constraints:** `(source_service, processed_at DESC)`. Insert the event ID in the same local transaction as the projection update. If the ID already exists, do not apply the same event twice. Also check source versions to avoid stale/out-of-order updates.

## 7.9 `calendar_holidays`

One record per named holiday/type combination. The same date may have more than one holiday record.

| Column | Type | Rules / purpose |
|---|---|---|
| `id` | UUID | PK, default `gen_random_uuid()` |
| `holiday_date` | DATE | NOT NULL |
| `name_en` | VARCHAR(200) | NOT NULL |
| `name_si` | VARCHAR(200) | NULL |
| `name_ta` | VARCHAR(200) | NULL |
| `holiday_type` | VARCHAR(24) | NOT NULL; CHECK in `BANK`, `PUBLIC`, `MERCANTILE`, `POYA` |
| `is_bank_holiday` | BOOLEAN | NOT NULL, default `false` |
| `is_public_holiday` | BOOLEAN | NOT NULL, default `false` |
| `is_mercantile_holiday` | BOOLEAN | NOT NULL, default `false` |
| `is_poya_day` | BOOLEAN | NOT NULL, default `false` |
| `source_name` | VARCHAR(200) | NOT NULL; e.g. Central Bank / Government calendar |
| `source_url` | TEXT | NULL; source document/API URL |
| `source_document_year` | SMALLINT | NOT NULL |
| `source_verified_at` | TIMESTAMPTZ | NULL; last official-source verification time |
| `imported_at` | TIMESTAMPTZ | NOT NULL, default `now()` |
| `updated_at` | TIMESTAMPTZ | NOT NULL, default `now()` |

**Indexes/constraints:** index `(holiday_date)` and `(holiday_date, is_bank_holiday)`. Use a unique constraint such as `(holiday_date, name_en, holiday_type, source_document_year)` to avoid duplicate rows from re-imports while allowing multiple holidays on the same date. Import from a reliable official Sri Lankan source, retain source metadata, and re-verify each year's file. Do not automatically shift cash-flow forecasts for holidays until that business rule is agreed.

---

# 8. Data ownership and cross-service references

| Data | Source of truth | Projection/copy |
|---|---|---|
| Company, identity, roles, sessions | Identity Service | Other services use authenticated claims/IDs, not DB foreign keys |
| Daily Sales and Sales import approval | Cash Management | No separate Forecast Sales-history table in v1 |
| Issued/cancelled Cheques | Payment Service | `forecast_cheques` |
| Bank account, balance, transactions/category | Bank Integration | `forecast_bank_balance_projection`, `forecast_bank_transaction_projection` |
| Forecast run and daily results | Forecast Service | Read through Forecast API for Dashboard, Forecast, and Forecast-vs-Actual |
| Sri Lankan holiday data | Forecast/Calendar module | `calendar_holidays` |

Foreign keys are permitted only within the same service database. For example, `daily_sales.import_batch_id` can reference `sales_import_batches`; `forecast_runs.config_id` can reference `forecast_model_configs`. `company_id`, `user_id`, `bank_account_id` or `cheque_id` referring to other services are stored as IDs and validated through service APIs/events or trusted authenticated context.

# 9. Deliberately excluded from MVP

- `password_reset_tokens` — add when password reset is implemented.
- `bank_sync_runs` — add if/when live bank API synchronization is implemented; statement-import status is held in `bank_statement_import_batches` for now.
- `forecast_sales_history` — removed from the forecast inputs because forecast inflows are based on historical bank cash flows.
- `forecast_audit_events` — removed as a separate MVP table; run/config history is retained. Add an independent audit table if audit requirements later demand it.
- Forecast's `outbox_events` — not required until Forecast publishes downstream events (for example, notifications).
- Notification Service tables — notifications are not part of the core MVP.
- API Gateway database tables — Gateway handles routing/security, not business records.
- Bank reconciliation/matching tables — explicitly deferred.

# 10. Important open decisions before production migration

These do not require more tables immediately, but need explicit decisions before related logic is released:

1. Role permission matrix; whether a platform-level `SYSTEM_ADMIN` exists separately from company roles.
2. Exact forecasting lookback period, weekday/seasonality method, minimum amount of clean history, and missing-data behavior.
3. How bank transaction categories are reliably supplied. Unclassified data must be shown as incomplete, not guessed.
4. Treatment of issued cheques past expected clearing date; defer until Bank Reconciliation is designed.
5. How stale or incomplete bank data appears in UI and whether forecasts can run when data is stale.
6. Forecast-vs-Actual baseline run-selection rule (recommendation: retain an appropriate saved forecast run for the period being measured).
7. Duplicate file policy for Sales and bank statement imports.
8. Holiday calendar update/import process and the future rule, if any, for shifting expected cash-flow dates around bank holidays.

## 11. Implementation order after schema review

1. Review this logical schema and settle the open policies that affect constraints/columns.
2. Convert each service's tables to versioned PostgreSQL migration files.
3. Add database-level constraints/indexes and migration tests.
4. Implement service entities/repositories and transactional outbox/consumer idempotency.
5. Test tenant isolation, duplicate imports, audit immutability, stale bank data, and reproducible forecast runs before wiring the UI to APIs.
