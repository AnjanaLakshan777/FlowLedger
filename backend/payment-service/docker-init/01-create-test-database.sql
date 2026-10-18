-- The payment-service test suite uses its own database, so tests never share outbox rows with a
-- payment-service running locally (src/test/resources/config/application.properties).
-- Runs only when the payment-db volume is first created; for an existing volume see README.md.
CREATE DATABASE flowledger_payment_test;
