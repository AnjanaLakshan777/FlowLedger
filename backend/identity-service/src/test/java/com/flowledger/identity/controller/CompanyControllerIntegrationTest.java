package com.flowledger.identity.controller;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Needs the local PostgreSQL database from application.properties. Each test is rolled back.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class CompanyControllerIntegrationTest {

    private static final String COMPANIES_URL = "/api/v1/identity/companies";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void shouldPersistCompanyWithDatabaseGeneratedValues() throws Exception {
        MvcResult result = mockMvc.perform(post(COMPANIES_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "  ABC Trading (Pvt) Ltd  ", "registrationNumber": "PV12345"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id", notNullValue()))
                .andExpect(jsonPath("$.name").value("ABC Trading (Pvt) Ltd"))
                .andExpect(jsonPath("$.registrationNumber").value("PV12345"))
                .andExpect(jsonPath("$.defaultCurrency").value("LKR"))
                .andExpect(jsonPath("$.timezone").value("Asia/Colombo"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.createdAt", notNullValue()))
                .andReturn();

        UUID id = UUID.fromString(JsonPath.read(
                result.getResponse().getContentAsString(), "$.id"));

        Map<String, Object> row = jdbcTemplate.queryForMap("SELECT * FROM companies WHERE id = ?", id);
        assertThat(row.get("name")).isEqualTo("ABC Trading (Pvt) Ltd");
        assertThat(row.get("registration_number")).isEqualTo("PV12345");
        assertThat(row.get("default_currency")).isEqualTo("LKR");
        assertThat(row.get("timezone")).isEqualTo("Asia/Colombo");
        assertThat(row.get("status")).isEqualTo("ACTIVE");
        assertThat(row.get("created_at")).isNotNull();
        assertThat(row.get("updated_at")).isNotNull();
    }

    @Test
    void shouldCreateCompanyWithoutRegistrationNumber() throws Exception {
        mockMvc.perform(post(COMPANIES_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Colombo Spices (Pvt) Ltd"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id", notNullValue()))
                .andExpect(jsonPath("$.registrationNumber", nullValue()));
    }

    @Test
    void shouldRejectInvalidRequestWithoutSaving() throws Exception {
        Integer before = jdbcTemplate.queryForObject("SELECT count(*) FROM companies", Integer.class);

        mockMvc.perform(post(COMPANIES_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": ""}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.name").value("name must not be blank"));

        Integer after = jdbcTemplate.queryForObject("SELECT count(*) FROM companies", Integer.class);
        assertThat(after).isEqualTo(before);
    }
}
