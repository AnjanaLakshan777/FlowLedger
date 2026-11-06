package com.flowledger.forecast.holiday;

import com.flowledger.forecast.support.FakeIdentityServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Holidays: the Central Bank's 2026 and 2027 lists seeded by migration V8, read by every member,
 * replaced by a platform SYSTEM_ADMIN. Needs the local forecast database; each test is rolled back.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class HolidayIntegrationTest {

    private static final FakeIdentityServer identityService = FakeIdentityServer.instance();

    @DynamicPropertySource
    static void serviceUrls(DynamicPropertyRegistry registry) {
        registry.add("flowledger.identity.base-url", identityService::baseUrl);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UUID companyId;

    @BeforeEach
    void setUp() {
        companyId = UUID.randomUUID();
        identityService.reset();
    }

    @Test
    void shouldListTheSeededHolidaysForEveryMember() throws Exception {
        identityService.grant(companyId, "CASHIER");

        list("2026-11-01", "2026-12-31")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(4)))
                .andExpect(jsonPath("$[0].date").value("2026-11-08"))
                .andExpect(jsonPath("$[0].name").value("Deepawali Festival Day"))
                .andExpect(jsonPath("$[0].bankHoliday").value(true))
                .andExpect(jsonPath("$[0].mercantileHoliday").value(false))
                .andExpect(jsonPath("$[1].poyaDay").value(true))
                .andExpect(jsonPath("$[3].name").value("Christmas Day"))
                .andExpect(jsonPath("$[3].mercantileHoliday").value(true));
        // Two holidays on one day: Vesak and May Day.
        list("2026-05-01", "2026-05-01").andExpect(jsonPath("$", hasSize(2)));
        mockMvc.perform(get(base() + "/years").with(user()))
                .andExpect(jsonPath("$[*].year").value(hasItem(2026)))
                .andExpect(jsonPath("$[*].year").value(hasItem(2027)));
        list("2026-12-31", "2026-01-01").andExpect(status().isBadRequest());
    }

    @Test
    void shouldLetAPlatformAdminReplaceAYear() throws Exception {
        identityService.grantPlatformAdmin(companyId);

        upload(2027, "date,name,codes\n2027-01-15,Tamil Thai Pongal Day,B. P. M.\n2027-12-25,Christmas Day,B. P. M.\n")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)));

        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM calendar_holidays WHERE source_document_year = 2027",
                Integer.class)).isEqualTo(2);
        // Other years are untouched.
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM calendar_holidays WHERE source_document_year = 2026",
                Integer.class)).isEqualTo(26);
    }

    @Test
    void shouldRefuseAnInvalidFileAndKeepTheYear() throws Exception {
        identityService.grantPlatformAdmin(companyId);

        upload(2027, "date,name,codes\n2026-12-25,Christmas Day,B P M\n")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0]").value(org.hamcrest.Matchers.containsString("is not in 2027")));
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM calendar_holidays WHERE source_document_year = 2027",
                Integer.class)).isEqualTo(25);
    }

    @Test
    void shouldKeepCompanyRolesFromReplacingTheSharedList() throws Exception {
        identityService.grant(companyId, "COMPANY_ADMIN");
        upload(2027, "date,name,codes\n2027-12-25,Christmas Day,B P M\n").andExpect(status().isForbidden());
    }

    private ResultActions list(String from, String to) throws Exception {
        return mockMvc.perform(get(base() + "?from=" + from + "&to=" + to).with(user()));
    }

    private ResultActions upload(int year, String csv) throws Exception {
        return mockMvc.perform(multipart(base() + "/" + year)
                .file(new MockMultipartFile("file", "holidays.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8)))
                .with(request -> { request.setMethod("PUT"); return request; })
                .with(user()));
    }

    private String base() {
        return "/api/v1/forecast/companies/" + companyId + "/holidays";
    }

    private static RequestPostProcessor user() {
        return jwt().jwt(jwt -> jwt.subject(UUID.randomUUID().toString()));
    }
}
