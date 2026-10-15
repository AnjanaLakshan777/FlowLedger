package com.flowledger.payment.repository;

import com.flowledger.payment.dto.ChequeSummaryResponse;
import com.flowledger.payment.dto.InternalChequePage;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Read-only queries across all companies for the internal API. Each method is one statement, so
 * each result is consistent in itself; consecutive calls are not.
 */
@Repository
@RequiredArgsConstructor
public class ChequeScanStore {

    private static final String SELECT_ITEMS = """
            SELECT id, company_id, bank_account_id, cheque_number, category, amount, issue_date,
                   expected_clearing_date, status, version
            FROM cheques
            """;

    private static final RowMapper<InternalChequePage.Item> ITEM = (rs, rowNum) -> new InternalChequePage.Item(
            rs.getObject("id", UUID.class),
            rs.getObject("company_id", UUID.class),
            rs.getObject("bank_account_id", UUID.class),
            rs.getString("cheque_number"),
            rs.getString("category"),
            rs.getBigDecimal("amount").toPlainString(),
            rs.getDate("issue_date").toLocalDate().toString(),
            rs.getDate("expected_clearing_date").toLocalDate().toString(),
            rs.getString("status"),
            rs.getInt("version"));

    private static final RowMapper<ChequeSummaryResponse.Company> COMPANY = (rs, rowNum) ->
            new ChequeSummaryResponse.Company(
                    rs.getObject("company_id", UUID.class),
                    rs.getLong("cheque_count"),
                    rs.getLong("issued_count"),
                    rs.getLong("cancelled_count"),
                    rs.getLong("version_sum"));

    private final JdbcTemplate jdbcTemplate;

    // Up to limit cheques with an ID greater than afterId (all cheques when null), by ID.
    public List<InternalChequePage.Item> page(UUID afterId, int limit) {
        if (afterId == null) {
            return jdbcTemplate.query(SELECT_ITEMS + "ORDER BY id LIMIT ?", ITEM, limit);
        }
        return jdbcTemplate.query(SELECT_ITEMS + "WHERE id > ? ORDER BY id LIMIT ?", ITEM, afterId, limit);
    }

    // Every company with cheques, or just the given one (no entry if it has none).
    public List<ChequeSummaryResponse.Company> summary(UUID companyId) {
        String sql = """
                SELECT company_id,
                       count(*) AS cheque_count,
                       count(*) FILTER (WHERE status = 'ISSUED') AS issued_count,
                       count(*) FILTER (WHERE status = 'CANCELLED') AS cancelled_count,
                       coalesce(sum(version), 0) AS version_sum
                FROM cheques
                %s
                GROUP BY company_id
                ORDER BY company_id
                """;
        if (companyId == null) {
            return jdbcTemplate.query(sql.formatted(""), COMPANY);
        }
        return jdbcTemplate.query(sql.formatted("WHERE company_id = ?"), COMPANY, companyId);
    }
}
