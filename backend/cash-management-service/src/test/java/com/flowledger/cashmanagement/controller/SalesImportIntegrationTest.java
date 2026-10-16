package com.flowledger.cashmanagement.controller;

import com.flowledger.cashmanagement.support.FakeIdentityServer;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Historical sales import through the API: upload and validation, review, approval and rejection,
 * roles, company isolation and audit. Membership answers come from FakeIdentityServer. Needs the
 * local cash-management PostgreSQL database. Each test is rolled back.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@ExtendWith(OutputCaptureExtension.class)
class SalesImportIntegrationTest {

    private static final FakeIdentityServer identityService = FakeIdentityServer.instance();

    @DynamicPropertySource
    static void identityServiceUrl(DynamicPropertyRegistry registry) {
        registry.add("flowledger.identity.base-url", identityService::baseUrl);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UUID companyId;
    private UUID accountant;
    private UUID financeController;
    private LocalDate today;

    @BeforeEach
    void setUp() {
        companyId = UUID.randomUUID();
        accountant = UUID.randomUUID();
        financeController = UUID.randomUUID();
        today = LocalDate.now(ZoneId.of("Asia/Colombo"));
        identityService.reset();
    }

    // Upload and validation

    @Test
    void shouldValidateAValidFileAndWaitForApproval() throws Exception {
        identityService.grant(companyId, "ACCOUNTANT");

        upload(accountant, "history.csv", csv("2025-01-01,100.00,200.50", "2025-01-02,0,0", "2025-01-03,10,0"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.batch.status").value("PENDING_APPROVAL"))
                .andExpect(jsonPath("$.batch.fileName").value("history.csv"))
                .andExpect(jsonPath("$.batch.rowCount").value(3))
                .andExpect(jsonPath("$.batch.validRowCount").value(3))
                .andExpect(jsonPath("$.batch.invalidRowCount").value(0))
                .andExpect(jsonPath("$.batch.duplicateRowCount").value(0))
                .andExpect(jsonPath("$.batch.cashSalesTotal").value(110.00))
                .andExpect(jsonPath("$.batch.cardSalesTotal").value(200.50))
                .andExpect(jsonPath("$.batch.periodStart").value("2025-01-01"))
                .andExpect(jsonPath("$.batch.periodEnd").value("2025-01-03"))
                .andExpect(jsonPath("$.batch.uploadedBy").value(accountant.toString()))
                .andExpect(jsonPath("$.batch.version").value(1))
                .andExpect(jsonPath("$.rows", hasSize(3)))
                .andExpect(jsonPath("$.rows[1].cashSalesAmount").value(0.0))
                .andExpect(jsonPath("$.rows[1].status").value("VALID"))
                .andExpect(jsonPath("$.rows[1].raw").value(nullValue()));

        // Nothing reaches daily sales before approval.
        assertThat(count("SELECT count(*) FROM daily_sales WHERE company_id = ?")).isZero();
        assertThat(jdbcTemplate.queryForList("""
                SELECT action FROM cash_management_audit_events WHERE company_id = ?
                """, String.class, companyId)).containsExactly("SALES_IMPORT_UPLOADED");
    }

    @Test
    void shouldShowInvalidRowsWithReasonsAndStillWaitForApproval() throws Exception {
        identityService.grant(companyId, "ACCOUNTANT");

        upload(accountant, "history.csv", csv("2025-01-01,100,100", "2025-01-02,,50", "2025-01-03,-5,0",
                "2025/01/04,1,1", today.plusDays(1) + ",1,1"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.batch.status").value("PENDING_APPROVAL"))
                .andExpect(jsonPath("$.batch.validRowCount").value(1))
                .andExpect(jsonPath("$.batch.invalidRowCount").value(4))
                .andExpect(jsonPath("$.rows[1].status").value("INVALID"))
                .andExpect(jsonPath("$.rows[1].errors[0].code").value("MISSING"))
                .andExpect(jsonPath("$.rows[1].raw.cash_sales").value(""))
                .andExpect(jsonPath("$.rows[2].errors[0].code").value("NEGATIVE"))
                .andExpect(jsonPath("$.rows[3].errors[0].code").value("INVALID_FORMAT"))
                .andExpect(jsonPath("$.rows[3].raw.date").value("2025/01/04"))
                .andExpect(jsonPath("$.rows[4].errors[0].code").value("FUTURE_DATE"));
    }

    @Test
    void shouldBlockAFileWithDuplicateDates() throws Exception {
        identityService.grant(companyId, "ACCOUNTANT");

        String body = upload(accountant, "history.csv", csv("2025-01-01,1,1", "2025-01-02,2,2", "2025-01-01,3,3"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.batch.status").value("INVALID"))
                .andExpect(jsonPath("$.batch.duplicateRowCount").value(2))
                .andExpect(jsonPath("$.rows[0].status").value("DUPLICATE"))
                .andExpect(jsonPath("$.rows[0].duplicateDate").value(true))
                .andExpect(jsonPath("$.rows[0].errors[0].code").value("DUPLICATE_IN_FILE"))
                .andExpect(jsonPath("$.rows[0].errors[0].message").value("This date is also on row 4. Keep one row per date."))
                .andExpect(jsonPath("$.rows[1].status").value("VALID"))
                .andReturn().getResponse().getContentAsString();

        identityService.grant(companyId, "FINANCE_CONTROLLER");
        approve(financeController, id(body), 1, false).andExpect(status().isConflict());
        assertThat(count("SELECT count(*) FROM daily_sales WHERE company_id = ?")).isZero();
    }

    @Test
    void shouldBlockDatesThatAlreadyHaveSales() throws Exception {
        insertSale(LocalDate.of(2025, 1, 2));
        identityService.grant(companyId, "ACCOUNTANT");

        upload(accountant, "history.csv", csv("2025-01-01,1,1", "2025-01-02,2,2"))
                .andExpect(jsonPath("$.batch.status").value("INVALID"))
                .andExpect(jsonPath("$.rows[1].status").value("DUPLICATE"))
                .andExpect(jsonPath("$.rows[1].duplicateDate").value(false))
                .andExpect(jsonPath("$.rows[1].errors[0].code").value("DATE_ALREADY_RECORDED"));
    }

    @Test
    void shouldRecordAnUnreadableFileAsInvalid() throws Exception {
        identityService.grant(companyId, "ACCOUNTANT");

        upload(accountant, "history.csv", "Date;Cash;Card\n2025-01-01;1;1\n")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.batch.status").value("INVALID"))
                .andExpect(jsonPath("$.batch.fileError").value("The first row must be exactly: date,cash_sales,card_payments"))
                .andExpect(jsonPath("$.batch.rowCount").value(0))
                .andExpect(jsonPath("$.rows", hasSize(0)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"history.xlsx", "history.txt", "history"})
    void shouldRefuseFilesThatAreNotCsv(String filename) throws Exception {
        identityService.grant(companyId, "ACCOUNTANT");

        upload(accountant, filename, csv("2025-01-01,1,1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.file").exists());
        assertThat(count("SELECT count(*) FROM sales_import_batches WHERE company_id = ?")).isZero();
    }

    @Test
    void shouldRefuseAnEmptyOrOversizedUpload() throws Exception {
        identityService.grant(companyId, "ACCOUNTANT");

        upload(accountant, "history.csv", "").andExpect(status().isBadRequest());
        upload(accountant, "history.csv", "x".repeat(1024 * 1024 + 1)).andExpect(status().isBadRequest());
        mockMvc.perform(multipart(importsUrl(companyId)).with(user(accountant))).andExpect(status().isBadRequest());
        assertThat(count("SELECT count(*) FROM sales_import_batches WHERE company_id = ?")).isZero();
    }

    @Test
    void shouldRefuseTheSameFileWhileItIsPendingOrApprovedButAllowItAfterRejection() throws Exception {
        identityService.grant(companyId, "FINANCE_CONTROLLER");
        String content = csv("2025-01-01,1,1");
        String first = id(upload(financeController, "a.csv", content).andReturn().getResponse().getContentAsString());

        upload(financeController, "renamed.csv", content)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.existingImportId").value(first));

        reject(financeController, first, 1, "Wrong year").andExpect(status().isOk());
        upload(financeController, "a.csv", content).andExpect(status().isCreated());
    }

    // Approval

    @Test
    void shouldRecordApprovedRowsAsDailySalesWithAuditAndKeepZeroDays() throws Exception {
        identityService.grant(companyId, "ACCOUNTANT");
        String id = id(upload(accountant, "history.csv", csv("2025-01-01,100.00,200.50", "2025-01-02,0,0"))
                .andReturn().getResponse().getContentAsString());

        identityService.grant(companyId, "FINANCE_CONTROLLER");
        approve(financeController, id, 1, false, "Checked against the 2025 accounts")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.batch.status").value("APPROVED"))
                .andExpect(jsonPath("$.batch.approvedBy").value(financeController.toString()))
                .andExpect(jsonPath("$.batch.approvedAt").exists())
                .andExpect(jsonPath("$.batch.approvalNote").value("Checked against the 2025 accounts"))
                .andExpect(jsonPath("$.batch.importedRowCount").value(2))
                .andExpect(jsonPath("$.batch.excludedRowCount").value(0))
                .andExpect(jsonPath("$.batch.uploadedBy").value(accountant.toString()))
                .andExpect(jsonPath("$.batch.version").value(2));

        Map<String, Object> sale = jdbcTemplate.queryForMap(
                "SELECT * FROM daily_sales WHERE company_id = ? AND sale_date = DATE '2025-01-01'", companyId);
        assertThat(sale).containsEntry("entry_source", "HISTORICAL_IMPORT")
                .containsEntry("import_batch_id", UUID.fromString(id))
                .containsEntry("recorded_by", accountant);
        assertThat((BigDecimal) sale.get("card_sales_amount")).isEqualByComparingTo("200.50");
        // A day with no sales is not a daily sales record; it stays an approved import row.
        assertThat(count("SELECT count(*) FROM daily_sales WHERE company_id = ?")).isEqualTo(1);

        mockMvc.perform(get("/api/v1/cash-management/companies/" + companyId + "/sales").with(user(financeController)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].entrySource").value("HISTORICAL_IMPORT"))
                .andExpect(jsonPath("$[0].bankDepositDate").doesNotExist());

        Map<String, Object> event = jdbcTemplate.queryForMap("""
                SELECT actor_user_id, entity_type, entity_id, reason, after_data::text AS after
                FROM cash_management_audit_events WHERE company_id = ? AND action = 'SALES_IMPORT_APPROVED'
                """, companyId);
        assertThat(event).containsEntry("actor_user_id", financeController)
                .containsEntry("entity_type", "SALES_IMPORT_BATCH")
                .containsEntry("entity_id", UUID.fromString(id))
                .containsEntry("reason", "Checked against the 2025 accounts");
        assertThat((String) event.get("after")).contains("\"dailySalesCreated\": 1");
    }

    @Test
    void shouldRequireAnExplicitDecisionToLeaveOutInvalidRows() throws Exception {
        identityService.grant(companyId, "FINANCE_CONTROLLER");
        String id = id(upload(financeController, "history.csv", csv("2025-01-01,1,1", "2025-01-02,,1"))
                .andReturn().getResponse().getContentAsString());

        approve(financeController, id, 1, false)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.excludeInvalidRows").exists());
        assertThat(count("SELECT count(*) FROM daily_sales WHERE company_id = ?")).isZero();

        approve(financeController, id, 1, true)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.batch.importedRowCount").value(1))
                .andExpect(jsonPath("$.batch.excludedRowCount").value(1));
        assertThat(count("SELECT count(*) FROM daily_sales WHERE company_id = ?")).isEqualTo(1);
    }

    @Test
    void shouldRefuseTheWholeImportWhenADateWasRecordedAfterTheUpload() throws Exception {
        identityService.grant(companyId, "FINANCE_CONTROLLER");
        String id = id(upload(financeController, "history.csv", csv("2025-01-01,1,1", "2025-01-02,2,2", "2025-01-03,3,3"))
                .andReturn().getResponse().getContentAsString());
        insertSale(LocalDate.of(2025, 1, 2));

        approve(financeController, id, 1, false)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.conflictingDates", contains("2025-01-02")));

        // Nothing was recorded; the import still waits and can be rejected.
        assertThat(count("SELECT count(*) FROM daily_sales WHERE company_id = ?")).isEqualTo(1);
        read(financeController, id).andExpect(jsonPath("$.batch.status").value("PENDING_APPROVAL"));
        reject(financeController, id, 1, "Overlaps recorded sales").andExpect(status().isOk());
    }

    @Test
    void shouldRefuseADatePreviouslyApprovedAsAZeroDay() throws Exception {
        identityService.grant(companyId, "FINANCE_CONTROLLER");
        String first = id(upload(financeController, "a.csv", csv("2025-01-01,0,0"))
                .andReturn().getResponse().getContentAsString());
        approve(financeController, first, 1, false).andExpect(status().isOk());

        upload(financeController, "b.csv", csv("2025-01-01,5,5"))
                .andExpect(jsonPath("$.batch.status").value("INVALID"))
                .andExpect(jsonPath("$.rows[0].errors[0].code").value("DATE_ALREADY_RECORDED"));
    }

    @Test
    void shouldDecideAnImportOnlyOnceAndOnlyAtTheReviewedVersion() throws Exception {
        identityService.grant(companyId, "FINANCE_CONTROLLER");
        String id = id(upload(financeController, "history.csv", csv("2025-01-01,1,1"))
                .andReturn().getResponse().getContentAsString());

        approve(financeController, id, 7, false).andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("This import was changed by someone else. Reload it and try again."));
        approve(financeController, id, 1, false).andExpect(status().isOk());
        approve(financeController, id, 1, false).andExpect(status().isConflict());
        approve(financeController, id, 2, false).andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("This import is not waiting for approval."));
        reject(financeController, id, 2, "Too late").andExpect(status().isConflict());
        assertThat(count("SELECT count(*) FROM daily_sales WHERE company_id = ?")).isEqualTo(1);
    }

    // Rejection

    @Test
    void shouldRejectWithAReasonAndAudit() throws Exception {
        identityService.grant(companyId, "FINANCE_CONTROLLER");
        String id = id(upload(financeController, "history.csv", csv("2025-01-01,1,1"))
                .andReturn().getResponse().getContentAsString());

        reject(financeController, id, 1, "  ").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.reason").value("reason is required"));
        reject(financeController, id, 1, "Totals do not match the ledger")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.batch.status").value("REJECTED"))
                .andExpect(jsonPath("$.batch.rejectedBy").value(financeController.toString()))
                .andExpect(jsonPath("$.batch.rejectionReason").value("Totals do not match the ledger"));

        approve(financeController, id, 2, false).andExpect(status().isConflict());
        assertThat(count("SELECT count(*) FROM daily_sales WHERE company_id = ?")).isZero();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT reason FROM cash_management_audit_events WHERE company_id = ? AND action = 'SALES_IMPORT_REJECTED'
                """, String.class, companyId)).isEqualTo("Totals do not match the ledger");
    }

    // Roles and companies

    @Test
    void shouldKeepCashiersOut() throws Exception {
        identityService.grant(companyId, "FINANCE_CONTROLLER");
        String id = id(upload(financeController, "history.csv", csv("2025-01-01,1,1"))
                .andReturn().getResponse().getContentAsString());
        identityService.grant(companyId, "CASHIER");
        UUID cashier = UUID.randomUUID();

        mockMvc.perform(get(importsUrl(companyId)).with(user(cashier))).andExpect(status().isForbidden());
        read(cashier, id).andExpect(status().isForbidden());
        upload(cashier, "b.csv", csv("2025-02-01,1,1")).andExpect(status().isForbidden());
        approve(cashier, id, 1, false).andExpect(status().isForbidden());
        reject(cashier, id, 1, "No").andExpect(status().isForbidden());
    }

    @Test
    void shouldLetAccountantsUploadAndSeeOnlyTheirOwnImportsButNotDecide() throws Exception {
        identityService.grant(companyId, "FINANCE_CONTROLLER");
        String byController = id(upload(financeController, "fc.csv", csv("2025-01-01,1,1"))
                .andReturn().getResponse().getContentAsString());
        identityService.grant(companyId, "ACCOUNTANT");
        String own = id(upload(accountant, "own.csv", csv("2025-02-01,1,1"))
                .andReturn().getResponse().getContentAsString());

        mockMvc.perform(get(importsUrl(companyId)).with(user(accountant)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.canUpload").value(true))
                .andExpect(jsonPath("$.canApprove").value(false))
                .andExpect(jsonPath("$.ownImportsOnly").value(true))
                .andExpect(jsonPath("$.imports[*].id", contains(own)));
        read(accountant, own).andExpect(status().isOk());
        read(accountant, byController).andExpect(status().isNotFound());
        approve(accountant, own, 1, false).andExpect(status().isForbidden());
        reject(accountant, own, 1, "No").andExpect(status().isForbidden());
    }

    @Test
    void shouldLetFinanceControllersSeeEveryImport() throws Exception {
        identityService.grant(companyId, "ACCOUNTANT");
        String byAccountant = id(upload(accountant, "own.csv", csv("2025-02-01,1,1"))
                .andReturn().getResponse().getContentAsString());
        identityService.grant(companyId, "FINANCE_CONTROLLER");

        mockMvc.perform(get(importsUrl(companyId)).with(user(financeController)))
                .andExpect(jsonPath("$.canUpload").value(true))
                .andExpect(jsonPath("$.canApprove").value(true))
                .andExpect(jsonPath("$.ownImportsOnly").value(false))
                .andExpect(jsonPath("$.imports[*].id", contains(byAccountant)));
        read(financeController, byAccountant).andExpect(status().isOk());
    }

    @Test
    void shouldLetCompanyAdminsInspectAndDecideButNotUpload() throws Exception {
        identityService.grant(companyId, "ACCOUNTANT");
        String toApprove = id(upload(accountant, "a.csv", csv("2025-02-01,1,1"))
                .andReturn().getResponse().getContentAsString());
        String toReject = id(upload(accountant, "b.csv", csv("2025-02-02,1,1"))
                .andReturn().getResponse().getContentAsString());
        identityService.grant(companyId, "COMPANY_ADMIN");
        UUID admin = UUID.randomUUID();

        mockMvc.perform(get(importsUrl(companyId)).with(user(admin)))
                .andExpect(jsonPath("$.canUpload").value(false))
                .andExpect(jsonPath("$.canApprove").value(true))
                .andExpect(jsonPath("$.imports", hasSize(2)));
        upload(admin, "c.csv", csv("2025-03-01,1,1")).andExpect(status().isForbidden());

        approve(admin, toApprove, 1, false).andExpect(status().isOk())
                .andExpect(jsonPath("$.batch.status").value("APPROVED"))
                .andExpect(jsonPath("$.batch.approvedBy").value(admin.toString()));
        reject(admin, toReject, 1, "Wrong period").andExpect(status().isOk())
                .andExpect(jsonPath("$.batch.status").value("REJECTED"))
                .andExpect(jsonPath("$.batch.rejectedBy").value(admin.toString()));
        assertThat(count("SELECT count(*) FROM daily_sales WHERE company_id = ?")).isEqualTo(1);
    }

    @Test
    void shouldNotReachAnotherCompanysImport() throws Exception {
        identityService.grant(companyId, "FINANCE_CONTROLLER");
        String id = id(upload(financeController, "history.csv", csv("2025-01-01,1,1"))
                .andReturn().getResponse().getContentAsString());
        UUID otherCompany = UUID.randomUUID();
        identityService.grant(otherCompany, "FINANCE_CONTROLLER");

        // Through the user's own other company: the import is not found there.
        mockMvc.perform(get(importsUrl(otherCompany) + "/" + id).with(user(financeController)))
                .andExpect(status().isNotFound());
        mockMvc.perform(post(importsUrl(otherCompany) + "/" + id + "/approve").with(user(financeController))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\": 1}"))
                .andExpect(status().isNotFound());
        // A company the user does not belong to: refused before anything else.
        UUID foreign = UUID.randomUUID();
        mockMvc.perform(get(importsUrl(foreign)).with(user(financeController))).andExpect(status().isForbidden());

        read(financeController, id).andExpect(jsonPath("$.batch.status").value("PENDING_APPROVAL"));
        assertThat(count("SELECT count(*) FROM daily_sales WHERE company_id = ?")).isZero();
    }

    // Audit and logs

    @Test
    void shouldKeepAuditHistoryAppendOnly() throws Exception {
        identityService.grant(companyId, "ACCOUNTANT");
        upload(accountant, "history.csv", csv("2025-01-01,1,1")).andExpect(status().isCreated());

        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE cash_management_audit_events SET reason = 'changed' WHERE company_id = ?", companyId))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("append-only");
    }

    @Test
    void shouldNotLogRowContents(CapturedOutput output) throws Exception {
        identityService.grant(companyId, "FINANCE_CONTROLLER");
        int start = output.getAll().length();
        String id = id(upload(financeController, "secret-ledger.csv", csv("2019-03-07,98765.43,12345.67", "2019-03-08,-77.77,0"))
                .andReturn().getResponse().getContentAsString());
        approve(financeController, id, 1, true).andExpect(status().isOk());

        assertThat(output.getAll().substring(start)).contains(id)
                .doesNotContain("98765", "12345.67", "77.77", "2019-03-07");
    }

    // Helpers

    private static String csv(String... rows) {
        return "date,cash_sales,card_payments\n" + String.join("\n", rows) + "\n";
    }

    private ResultActions upload(UUID userId, String filename, String content) throws Exception {
        return mockMvc.perform(multipart(importsUrl(companyId))
                .file(new MockMultipartFile("file", filename, "text/csv", content.getBytes(StandardCharsets.UTF_8)))
                .with(user(userId)));
    }

    private ResultActions read(UUID userId, String id) throws Exception {
        return mockMvc.perform(MockMvcRequestBuilders.get(importsUrl(companyId) + "/" + id).with(user(userId)));
    }

    private ResultActions approve(UUID userId, String id, int version, boolean excludeInvalidRows) throws Exception {
        return approve(userId, id, version, excludeInvalidRows, null);
    }

    private ResultActions approve(UUID userId, String id, int version, boolean excludeInvalidRows, String note)
            throws Exception {
        String body = note == null
                ? "{\"version\": %d, \"excludeInvalidRows\": %s}".formatted(version, excludeInvalidRows)
                : "{\"version\": %d, \"excludeInvalidRows\": %s, \"note\": \"%s\"}".formatted(version, excludeInvalidRows,
                note);
        return mockMvc.perform(post(importsUrl(companyId) + "/" + id + "/approve").with(user(userId))
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions reject(UUID userId, String id, int version, String reason) throws Exception {
        return mockMvc.perform(post(importsUrl(companyId) + "/" + id + "/reject").with(user(userId))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\": %d, \"reason\": \"%s\"}".formatted(version, reason)));
    }

    private static String id(String body) {
        return JsonPath.read(body, "$.batch.id");
    }

    private static String importsUrl(UUID company) {
        return "/api/v1/cash-management/companies/" + company + "/sales-imports";
    }

    private static RequestPostProcessor user(UUID userId) {
        return jwt().jwt(jwt -> jwt.subject(userId.toString()).tokenValue("token-" + userId));
    }

    private int count(String sql) {
        return jdbcTemplate.queryForObject(sql, Integer.class, companyId);
    }

    private void insertSale(LocalDate date) {
        jdbcTemplate.update("""
                        INSERT INTO daily_sales
                            (company_id, sale_date, cash_sales_amount, card_sales_amount, entry_source, recorded_by)
                        VALUES (?, ?, 10.00, 0.00, 'UI', ?)
                        """,
                companyId, date, UUID.randomUUID());
    }

}
