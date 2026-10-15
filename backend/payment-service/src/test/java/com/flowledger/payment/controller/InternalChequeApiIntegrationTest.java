package com.flowledger.payment.controller;

import com.flowledger.payment.dto.ChequeSummaryResponse;
import com.flowledger.payment.dto.InternalChequePage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The internal cheque API against the local payment PostgreSQL database. Not @Transactional: the
 * concurrency tests need committed rows. The scan covers every company, including existing
 * development data, so assertions only look at this test's companies; test rows are deleted
 * afterwards and nothing else is changed.
 */
@SpringBootTest
@AutoConfigureMockMvc
class InternalChequeApiIntegrationTest {

    private static final String KEY_HEADER = "X-Internal-Api-Key";
    private static final String SCAN = "/internal/v1/payment/cheques";
    private static final String SUMMARY = "/internal/v1/payment/cheques/summary";
    // PostgreSQL orders UUIDs by unsigned bytes, which is the order of their lowercase text. Java's
    // UUID.compareTo compares signed halves and puts f... before 0..., so it must not be used here.
    private static final Comparator<UUID> DATABASE_ORDER = Comparator.comparing(UUID::toString);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private JsonMapper jsonMapper;

    @Value("${flowledger.security.internal-api.secret}")
    private String internalKey;

    @Value("${flowledger.identity.internal-api-secret}")
    private String identityKey;

    private final Set<UUID> companies = new HashSet<>();
    private int chequeNumber = 1;

    @AfterEach
    void deleteTestCheques() {
        companies.forEach(companyId -> jdbcTemplate.update("DELETE FROM cheques WHERE company_id = ?", companyId));
    }

    // Authentication

    @ParameterizedTest
    @ValueSource(strings = {SCAN, SUMMARY})
    void shouldRefuseRequestsWithoutTheKey(String path) throws Exception {
        mockMvc.perform(get(path))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value("Invalid service credentials."));
    }

    @ParameterizedTest
    @ValueSource(strings = {SCAN, SUMMARY})
    void shouldRefuseAWrongKey(String path) throws Exception {
        mockMvc.perform(get(path).header(KEY_HEADER, internalKey + "x")).andExpect(status().isUnauthorized());
        mockMvc.perform(get(path).header(KEY_HEADER, internalKey.substring(1))).andExpect(status().isUnauthorized());
        mockMvc.perform(get(path).header(KEY_HEADER, "")).andExpect(status().isUnauthorized());
        // The identity-service key is a different credential.
        mockMvc.perform(get(path).header(KEY_HEADER, identityKey)).andExpect(status().isUnauthorized());
    }

    @Test
    void shouldNotOfferTheInternalApiUnderThePublicPath() throws Exception {
        mockMvc.perform(get("/api/v1/payment/internal/v1/payment/cheques").header(KEY_HEADER, internalKey))
                .andExpect(status().isUnauthorized());
    }

    // Scan

    @Test
    void shouldReturnEveryChequeExactlyOnceInIdOrderAcrossPages() throws Exception {
        UUID companyId = company();
        Set<UUID> inserted = new HashSet<>();
        for (int i = 0; i < 7; i++) {
            inserted.add(insert(companyId, UUID.randomUUID(), "ISSUED", "2026-10-20", 1));
        }

        List<InternalChequePage> pages = scanPages(null, 3);
        List<UUID> all = pages.stream().flatMap(page -> page.items().stream())
                .map(InternalChequePage.Item::chequeId).toList();

        assertThat(all).containsAll(inserted);
        assertThat(all).doesNotHaveDuplicates();
        assertThat(all).isSortedAccordingTo(DATABASE_ORDER);
        for (int i = 0; i < pages.size(); i++) {
            InternalChequePage page = pages.get(i);
            assertThat(page.items()).hasSizeLessThanOrEqualTo(3);
            if (i < pages.size() - 1) {
                assertThat(page.items()).hasSize(3);
                assertThat(page.nextAfterId()).isEqualTo(page.items().getLast().chequeId());
            } else {
                assertThat(page.nextAfterId()).isNull();
            }
        }
    }

    @Test
    void shouldEndWithoutAnEmptyPageWhenTheLastPageIsFull() throws Exception {
        UUID companyId = company();
        UUID beforeLast = insert(companyId, UUID.fromString("ffffffff-ffff-ffff-ffff-fffffffffffe"), "ISSUED",
                "2026-10-20", 1);
        UUID last = insert(companyId, UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff"), "ISSUED",
                "2026-10-20", 1);
        UUID afterId = UUID.fromString("ffffffff-ffff-ffff-ffff-fffffffffffd");

        InternalChequePage full = page(afterId, 2);
        assertThat(full.items()).extracting(InternalChequePage.Item::chequeId).containsExactly(beforeLast, last);
        assertThat(full.nextAfterId()).isNull();

        InternalChequePage first = page(afterId, 1);
        assertThat(first.items()).extracting(InternalChequePage.Item::chequeId).containsExactly(beforeLast);
        assertThat(first.nextAfterId()).isEqualTo(beforeLast);
        InternalChequePage second = page(first.nextAfterId(), 1);
        assertThat(second.items()).extracting(InternalChequePage.Item::chequeId).containsExactly(last);
        assertThat(second.nextAfterId()).isNull();

        assertThat(page(last, 500).items()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"limit=0", "limit=-1", "limit=501", "limit=10000", "limit=abc", "afterId=not-a-uuid"})
    void shouldRejectInvalidPageParameters(String query) throws Exception {
        mockMvc.perform(internal(SCAN + "?" + query)).andExpect(status().isBadRequest());
    }

    @Test
    void shouldAcceptTheMaximumPageSizeAndDefaultToOneHundred() throws Exception {
        assertThat(page(null, 500).items()).hasSizeLessThanOrEqualTo(500);

        String body = mockMvc.perform(internal(SCAN)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(jsonMapper.readValue(body, InternalChequePage.class).items()).hasSizeLessThanOrEqualTo(100);
    }

    @Test
    void shouldReturnAllStatusesAndClearingDates() throws Exception {
        UUID companyId = company();
        UUID longPast = insert(companyId, UUID.randomUUID(), "ISSUED", "2001-01-01", 1);
        UUID farFuture = insert(companyId, UUID.randomUUID(), "ISSUED", "2099-12-31", 3);
        UUID cancelled = insert(companyId, UUID.randomUUID(), "CANCELLED", "2026-10-20", 2);

        Map<UUID, InternalChequePage.Item> items = itemsOf(companyId);

        assertThat(items).containsOnlyKeys(longPast, farFuture, cancelled);
        assertThat(items.get(longPast).expectedClearingDate()).isEqualTo("2001-01-01");
        assertThat(items.get(farFuture).expectedClearingDate()).isEqualTo("2099-12-31");
        assertThat(items.get(farFuture).version()).isEqualTo(3);
        InternalChequePage.Item item = items.get(cancelled);
        assertThat(item.status()).isEqualTo("CANCELLED");
        assertThat(item.companyId()).isEqualTo(companyId);
        assertThat(item.bankAccountId()).isEqualTo(UUID.fromString("5b0f4d3c-1a2b-4c5d-8e9f-0a1b2c3d4e5f"));
        assertThat(item.chequeNumber()).startsWith("00");
        assertThat(item.category()).isEqualTo("SUPPLIER_PAYMENT");
        assertThat(item.amount()).isEqualTo("1500.00");
        assertThat(item.issueDate()).isEqualTo("2001-01-01");
        assertThat(item.version()).isEqualTo(2);
    }

    @Test
    void shouldNotExposePayeeNotesOrAuditDetails() throws Exception {
        UUID companyId = company();
        UUID chequeId = insert(companyId, UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff"), "ISSUED",
                "2026-10-20", 1);

        String body = mockMvc.perform(internal(SCAN + "?afterId=ffffffff-ffff-ffff-ffff-fffffffffffe"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).contains(chequeId.toString())
                .doesNotContain("Secret Payee Holdings", "private settlement note", "payee", "notes",
                        "createdBy", "createdAt", "updatedBy", "updatedAt");
        @SuppressWarnings("unchecked")
        Map<String, Object> item = ((List<Map<String, Object>>) jsonMapper.readValue(body, Map.class).get("items"))
                .getFirst();
        assertThat(item).containsOnlyKeys("chequeId", "companyId", "bankAccountId", "chequeNumber", "category",
                "amount", "issueDate", "expectedClearingDate", "status", "version");
    }

    // Concurrent inserts: the scan does not see a cheque committed behind its cursor, and the
    // summary shows that the scan is incomplete.

    @Test
    void shouldMissAChequeInsertedBehindTheCursorAndShowItInTheSummary() throws Exception {
        UUID companyId = company();
        UUID start = UUID.fromString("f0000000-0000-0000-0000-000000000000");
        insert(companyId, UUID.fromString("f1000000-0000-0000-0000-000000000000"), "ISSUED", "2026-10-20", 1);
        insert(companyId, UUID.fromString("f3000000-0000-0000-0000-000000000000"), "ISSUED", "2026-10-20", 1);

        List<UUID> scanned = new ArrayList<>();
        InternalChequePage page = page(start, 1);
        page.items().forEach(item -> scanned.add(item.chequeId()));
        // Committed while the scan is between pages: one behind the cursor, one ahead of it.
        UUID behind = insert(companyId, UUID.fromString("f0500000-0000-0000-0000-000000000000"), "ISSUED",
                "2026-10-20", 1);
        UUID ahead = insert(companyId, UUID.fromString("f4000000-0000-0000-0000-000000000000"), "ISSUED",
                "2026-10-20", 1);
        scanned.addAll(scanFrom(page.nextAfterId(), 1));

        List<UUID> ofCompany = scanned.stream().filter(id -> id.toString().startsWith("f")).toList();
        assertThat(ofCompany).contains(ahead).doesNotContain(behind);
        assertThat(summaryOf(companyId).chequeCount()).isEqualTo(4);
        assertThat(countOf(companyId, scanned)).isEqualTo(3);

        // A new scan finds it.
        assertThat(itemsOf(companyId)).containsKey(behind).hasSize(4);
    }

    @Test
    void shouldReachAMatchingSummaryByRescanningWhileChequesAreInserted() throws Exception {
        UUID companyId = company();
        for (int i = 0; i < 20; i++) {
            insert(companyId, UUID.randomUUID(), "ISSUED", "2026-10-20", 1);
        }
        AtomicBoolean stop = new AtomicBoolean();
        CompletableFuture<Integer> writer = CompletableFuture.supplyAsync(() -> {
            int written = 0;
            while (!stop.get() && written < 40) {
                insert(companyId, UUID.randomUUID(), "ISSUED", "2026-10-20", 1);
                written++;
            }
            return written;
        });

        List<UUID> scanned;
        try {
            scanned = scanFrom(null, 5);
        } finally {
            stop.set(true);
        }
        int written = writer.get(30, TimeUnit.SECONDS);

        assertThat(scanned).doesNotHaveDuplicates().isSortedAccordingTo(DATABASE_ORDER);
        // Once writing stopped, a rescan matches the summary exactly.
        ChequeSummaryResponse.Company summary = summaryOf(companyId);
        assertThat(summary.chequeCount()).isEqualTo(20 + written);
        assertThat(countOf(companyId, scanFrom(null, 5))).isEqualTo(summary.chequeCount());
    }

    // Summary

    @Test
    void shouldSummariseEachCompany() throws Exception {
        UUID first = company();
        UUID second = company();
        insert(first, UUID.randomUUID(), "ISSUED", "2026-10-20", 1);
        insert(first, UUID.randomUUID(), "ISSUED", "2099-01-01", 4);
        insert(first, UUID.randomUUID(), "CANCELLED", "2001-01-01", 2);
        insert(second, UUID.randomUUID(), "CANCELLED", "2026-10-20", 5);

        ChequeSummaryResponse all = summary(null);
        assertThat(all.companies()).contains(
                new ChequeSummaryResponse.Company(first, 3, 2, 1, 7),
                new ChequeSummaryResponse.Company(second, 1, 0, 1, 5));
        assertThat(all.companies()).extracting(ChequeSummaryResponse.Company::companyId)
                .isSortedAccordingTo(DATABASE_ORDER).doesNotHaveDuplicates();
        assertThat(all.chequeCount())
                .isEqualTo(all.companies().stream().mapToLong(ChequeSummaryResponse.Company::chequeCount).sum());
        assertThat(all.chequeCount()).isGreaterThanOrEqualTo(4);

        ChequeSummaryResponse one = summary(first);
        assertThat(one.companies()).containsExactly(new ChequeSummaryResponse.Company(first, 3, 2, 1, 7));
        assertThat(one.chequeCount()).isEqualTo(3);
    }

    @Test
    void shouldAgreeWithTheScanAndTheDatabase() throws Exception {
        UUID companyId = company();
        insert(companyId, UUID.randomUUID(), "ISSUED", "2026-10-20", 2);
        insert(companyId, UUID.randomUUID(), "CANCELLED", "2026-10-21", 3);

        Map<UUID, InternalChequePage.Item> items = itemsOf(companyId);
        ChequeSummaryResponse.Company summary = summaryOf(companyId);

        assertThat(summary.chequeCount()).isEqualTo(items.size());
        assertThat(summary.versionSum())
                .isEqualTo(items.values().stream().mapToLong(InternalChequePage.Item::version).sum());
        assertThat(summary(null).chequeCount()).isEqualTo(
                jdbcTemplate.queryForObject("SELECT count(*) FROM cheques", Long.class));
    }

    @Test
    void shouldReturnNoEntryForACompanyWithoutCheques() throws Exception {
        ChequeSummaryResponse summary = summary(UUID.randomUUID());

        assertThat(summary.companies()).isEmpty();
        assertThat(summary.chequeCount()).isZero();
    }

    // Helpers

    private UUID company() {
        UUID companyId = UUID.randomUUID();
        companies.add(companyId);
        return companyId;
    }

    private synchronized String nextNumber() {
        return "%06d".formatted(chequeNumber++);
    }

    private UUID insert(UUID companyId, UUID chequeId, String status, String clearingDate, int version) {
        jdbcTemplate.update("""
                        INSERT INTO cheques (id, company_id, bank_account_id, cheque_number, payee_name, category,
                                             amount, issue_date, expected_clearing_date, status, notes, created_by,
                                             version)
                        VALUES (?, ?, '5b0f4d3c-1a2b-4c5d-8e9f-0a1b2c3d4e5f', ?, 'Secret Payee Holdings',
                                'SUPPLIER_PAYMENT', 1500.00, DATE '2001-01-01', ?::date, ?,
                                'private settlement note', ?, ?)
                        """,
                chequeId, companyId, nextNumber(), clearingDate, status, UUID.randomUUID(), version);
        return chequeId;
    }

    private MockHttpServletRequestBuilder internal(String path) {
        return get(path).header(KEY_HEADER, internalKey);
    }

    private InternalChequePage page(UUID afterId, int limit) throws Exception {
        String query = "?limit=" + limit + (afterId == null ? "" : "&afterId=" + afterId);
        String body = mockMvc.perform(internal(SCAN + query))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return jsonMapper.readValue(body, InternalChequePage.class);
    }

    private List<InternalChequePage> scanPages(UUID afterId, int limit) throws Exception {
        List<InternalChequePage> pages = new ArrayList<>();
        UUID cursor = afterId;
        do {
            InternalChequePage page = page(cursor, limit);
            pages.add(page);
            cursor = page.nextAfterId();
        } while (cursor != null);
        return pages;
    }

    private List<UUID> scanFrom(UUID afterId, int limit) throws Exception {
        return scanPages(afterId, limit).stream().flatMap(page -> page.items().stream())
                .map(InternalChequePage.Item::chequeId).toList();
    }

    private Map<UUID, InternalChequePage.Item> itemsOf(UUID companyId) throws Exception {
        Map<UUID, InternalChequePage.Item> items = new HashMap<>();
        for (InternalChequePage page : scanPages(null, 500)) {
            page.items().stream().filter(item -> item.companyId().equals(companyId))
                    .forEach(item -> items.put(item.chequeId(), item));
        }
        return items;
    }

    private long countOf(UUID companyId, List<UUID> scanned) {
        Set<UUID> ofCompany = new HashSet<>(jdbcTemplate.queryForList(
                "SELECT id FROM cheques WHERE company_id = ?", UUID.class, companyId));
        return scanned.stream().filter(ofCompany::contains).count();
    }

    private ChequeSummaryResponse summary(UUID companyId) throws Exception {
        String body = mockMvc.perform(internal(SUMMARY + (companyId == null ? "" : "?companyId=" + companyId)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return jsonMapper.readValue(body, ChequeSummaryResponse.class);
    }

    private ChequeSummaryResponse.Company summaryOf(UUID companyId) throws Exception {
        return summary(companyId).companies().getFirst();
    }

}
