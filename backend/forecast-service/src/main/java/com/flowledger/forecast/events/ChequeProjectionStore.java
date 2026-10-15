package com.flowledger.forecast.events;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The forecast_cheques projection and the processed_events record. Callers run these in one
 * transaction per event.
 */
@Repository
@RequiredArgsConstructor
public class ChequeProjectionStore {

    static final String SOURCE_SERVICE = "payment-service";

    private final JdbcTemplate jdbcTemplate;
    private final JsonMapper jsonMapper;

    public record Current(UUID companyId, int sourceVersion) {
    }

    /**
     * Records the event as processed. Returns false if it already was. A concurrent transaction
     * inserting the same event ID makes this wait until that one commits or rolls back.
     */
    public boolean markProcessed(ChequeEvent event) {
        return jdbcTemplate.update("""
                        INSERT INTO processed_events (event_id, source_service, event_type)
                        VALUES (?, ?, ?)
                        ON CONFLICT (event_id) DO NOTHING
                        """,
                event.messageId(), SOURCE_SERVICE, event.eventType()) == 1;
    }

    public void recordOutcome(ChequeEvent event, String outcome) {
        String summary = jsonMapper.writeValueAsString(Map.of(
                "outcome", outcome,
                "chequeId", event.chequeId().toString(),
                "sourceVersion", event.version()));
        jdbcTemplate.update("UPDATE processed_events SET result_summary = ?::jsonb WHERE event_id = ?",
                summary, event.messageId());
    }

    /**
     * Inserts the cheque or replaces it with a newer version of the same company's cheque. Returns
     * false if nothing changed: the stored version is the same or newer, or the stored cheque
     * belongs to another company. Either way the existing row is then locked until commit.
     */
    public boolean upsert(ChequeEvent event) {
        return jdbcTemplate.update("""
                        INSERT INTO forecast_cheques
                            (cheque_id, company_id, bank_account_id, cheque_number, category, amount, issue_date,
                             expected_clearing_date, status, source_version, source_event_created_at, last_event_id)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        ON CONFLICT (cheque_id) DO UPDATE SET
                            bank_account_id = EXCLUDED.bank_account_id,
                            cheque_number = EXCLUDED.cheque_number,
                            category = EXCLUDED.category,
                            amount = EXCLUDED.amount,
                            issue_date = EXCLUDED.issue_date,
                            expected_clearing_date = EXCLUDED.expected_clearing_date,
                            status = EXCLUDED.status,
                            source_version = EXCLUDED.source_version,
                            source_event_created_at = EXCLUDED.source_event_created_at,
                            last_event_id = EXCLUDED.last_event_id,
                            projected_at = now()
                        WHERE forecast_cheques.company_id = EXCLUDED.company_id
                          AND forecast_cheques.source_version < EXCLUDED.source_version
                        """,
                event.chequeId(), event.companyId(), event.bankAccountId(), event.chequeNumber(), event.category(),
                event.amount(), event.issueDate(), event.expectedClearingDate(), event.status(), event.version(),
                event.sourceEventCreatedAt() == null ? null : event.sourceEventCreatedAt().atOffset(ZoneOffset.UTC),
                event.messageId()) == 1;
    }

    public Optional<Current> find(UUID chequeId) {
        List<Current> rows = jdbcTemplate.query(
                "SELECT company_id, source_version FROM forecast_cheques WHERE cheque_id = ?",
                (rs, rowNum) -> new Current(rs.getObject("company_id", UUID.class), rs.getInt("source_version")),
                chequeId);
        return rows.stream().findFirst();
    }
}
