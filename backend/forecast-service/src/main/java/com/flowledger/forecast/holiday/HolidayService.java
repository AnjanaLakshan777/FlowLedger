package com.flowledger.forecast.holiday;

import com.flowledger.forecast.security.CompanyAccessVerifier;
import com.flowledger.forecast.security.CompanyRole;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Sri Lankan holidays for the cash-flow calendar. Every member of a company may read them; only a
 * platform SYSTEM_ADMIN replaces a year's list, since the list is shared by every company. Company
 * access is checked with identity-service before any database work. Holidays do not change
 * forecasts (that rule is not agreed yet).
 */
@Slf4j
@Service
public class HolidayService {

    public record Holiday(LocalDate date, String name, boolean bankHoliday, boolean publicHoliday,
                          boolean mercantileHoliday, boolean poyaDay) {
    }

    public record Year(int year, int holidayCount, String sourceName, Instant importedAt) {
    }

    private static final Set<CompanyRole> READ_ROLES = EnumSet.allOf(CompanyRole.class);
    private static final int MAX_DAYS = 800;
    private static final long MAX_FILE_BYTES = 256 * 1024;
    private static final String SOURCE_NAME = "Central Bank of Sri Lanka";

    private final JdbcTemplate jdbcTemplate;
    private final CompanyAccessVerifier companyAccessVerifier;
    private final TransactionTemplate writeTransaction;

    public HolidayService(JdbcTemplate jdbcTemplate, CompanyAccessVerifier companyAccessVerifier,
                          PlatformTransactionManager transactionManager) {
        this.jdbcTemplate = jdbcTemplate;
        this.companyAccessVerifier = companyAccessVerifier;
        this.writeTransaction = new TransactionTemplate(transactionManager);
    }

    public List<Holiday> list(Jwt user, UUID companyId, LocalDate from, LocalDate to) {
        companyAccessVerifier.verify(user, companyId, READ_ROLES);
        if (from == null || to == null || to.isBefore(from) || ChronoUnit.DAYS.between(from, to) >= MAX_DAYS) {
            throw badRequest("from and to are required, to not before from, at most " + MAX_DAYS + " days apart.", List.of());
        }
        return jdbcTemplate.query("""
                        SELECT holiday_date, name_en, is_bank_holiday, is_public_holiday, is_mercantile_holiday, is_poya_day
                        FROM calendar_holidays WHERE holiday_date BETWEEN ? AND ?
                        ORDER BY holiday_date, name_en
                        """,
                (rs, n) -> new Holiday(rs.getObject("holiday_date", LocalDate.class), rs.getString("name_en"),
                        rs.getBoolean("is_bank_holiday"), rs.getBoolean("is_public_holiday"),
                        rs.getBoolean("is_mercantile_holiday"), rs.getBoolean("is_poya_day")),
                from, to);
    }

    public List<Year> years(Jwt user, UUID companyId) {
        companyAccessVerifier.verify(user, companyId, READ_ROLES);
        return jdbcTemplate.query("""
                        SELECT source_document_year, count(*) AS holiday_count, min(source_name) AS source_name,
                               max(imported_at) AS imported_at
                        FROM calendar_holidays GROUP BY source_document_year ORDER BY source_document_year
                        """,
                (rs, n) -> new Year(rs.getInt("source_document_year"), rs.getInt("holiday_count"),
                        rs.getString("source_name"), rs.getTimestamp("imported_at").toInstant()));
    }

    /** Replaces the year's whole list with the file's, so a corrected list never leaves old rows behind. */
    public List<Holiday> replaceYear(Jwt user, UUID companyId, int year, MultipartFile file) {
        companyAccessVerifier.verifyPlatformAdmin(user, companyId);
        if (year < 2000 || year > 2100) {
            throw badRequest("year must be between 2000 and 2100.", List.of());
        }
        if (file == null || file.isEmpty() || file.getSize() > MAX_FILE_BYTES) {
            throw badRequest("Choose a CSV file of at most 256 KB.", List.of());
        }
        byte[] content;
        try {
            content = file.getBytes();
        } catch (IOException e) {
            throw badRequest("The file could not be read.", List.of());
        }
        HolidayCsvParser.Result parsed = HolidayCsvParser.parse(content, year);
        if (!parsed.errors().isEmpty()) {
            throw badRequest("The holiday list was not imported.", parsed.errors());
        }
        UUID actor = UUID.fromString(user.getSubject());
        String sourceUrl = "https://www.cbsl.gov.lk/en/about/about-the-bank/bank-holidays-" + year;
        writeTransaction.executeWithoutResult(status -> {
            jdbcTemplate.update("DELETE FROM calendar_holidays WHERE source_document_year = ?", year);
            Timestamp now = Timestamp.from(Instant.now());
            jdbcTemplate.batchUpdate("""
                            INSERT INTO calendar_holidays (holiday_date, name_en, is_bank_holiday, is_public_holiday,
                                is_mercantile_holiday, is_poya_day, source_name, source_url, source_document_year,
                                imported_by, imported_at)
                            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                            """,
                    parsed.rows(), parsed.rows().size(), (ps, row) -> {
                        ps.setObject(1, row.date());
                        ps.setString(2, row.name());
                        ps.setBoolean(3, row.bank());
                        ps.setBoolean(4, row.publicHoliday());
                        ps.setBoolean(5, row.mercantile());
                        ps.setBoolean(6, row.poya());
                        ps.setString(7, SOURCE_NAME);
                        ps.setString(8, sourceUrl);
                        ps.setInt(9, year);
                        ps.setObject(10, actor);
                        ps.setTimestamp(11, now);
                    });
        });
        log.info("Holiday list for {} replaced: {} holidays", year, parsed.rows().size());
        return parsed.rows().stream()
                .map(row -> new Holiday(row.date(), row.name(), row.bank(), row.publicHoliday(), row.mercantile(), row.poya()))
                .toList();
    }

    private static ErrorResponseException badRequest(String detail, List<String> errors) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail);
        if (!errors.isEmpty()) {
            problem.setProperty("errors", errors);
        }
        return new ErrorResponseException(HttpStatus.BAD_REQUEST, problem, null);
    }
}
