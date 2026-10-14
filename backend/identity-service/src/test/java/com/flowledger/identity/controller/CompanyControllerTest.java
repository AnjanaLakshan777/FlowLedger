package com.flowledger.identity.controller;

import com.flowledger.identity.dto.CompanyResponse;
import com.flowledger.identity.dto.CreateCompanyRequest;
import com.flowledger.identity.entity.CompanyStatus;
import com.flowledger.identity.exception.GlobalExceptionHandler;
import com.flowledger.identity.service.CompanyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CompanyControllerTest {

    private static final String COMPANIES_URL = "/api/v1/identity/companies";

    private CompanyService companyService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        companyService = mock(CompanyService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new CompanyController(companyService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void shouldCreateCompany() throws Exception {
        UUID id = UUID.randomUUID();
        when(companyService.createCompany(any(CreateCompanyRequest.class))).thenReturn(new CompanyResponse(
                id, "ABC Trading (Pvt) Ltd", "PV12345", "LKR", "Asia/Colombo",
                CompanyStatus.ACTIVE, Instant.parse("2026-01-01T00:00:00Z")));

        mockMvc.perform(post(COMPANIES_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "ABC Trading (Pvt) Ltd", "registrationNumber": "PV12345"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.name").value("ABC Trading (Pvt) Ltd"))
                .andExpect(jsonPath("$.registrationNumber").value("PV12345"))
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        verify(companyService).createCompany(new CreateCompanyRequest("ABC Trading (Pvt) Ltd", "PV12345"));
    }

    @Test
    void shouldRejectBlankName() throws Exception {
        mockMvc.perform(post(COMPANIES_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "   "}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Validation failed"))
                .andExpect(jsonPath("$.errors.name").value("name must not be blank"));

        verify(companyService, never()).createCompany(any());
    }

    @Test
    void shouldRejectMissingName() throws Exception {
        mockMvc.perform(post(COMPANIES_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"registrationNumber": "PV12345"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.name").value("name must not be blank"));

        verify(companyService, never()).createCompany(any());
    }

    @Test
    void shouldRejectNameLongerThan200Characters() throws Exception {
        mockMvc.perform(post(COMPANIES_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"" + "A".repeat(201) + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.name").value("name must be at most 200 characters"));

        verify(companyService, never()).createCompany(any());
    }

    @Test
    void shouldRejectRegistrationNumberLongerThan80Characters() throws Exception {
        mockMvc.perform(post(COMPANIES_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"ABC\", \"registrationNumber\": \"" + "P".repeat(81) + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.registrationNumber")
                        .value("registrationNumber must be at most 80 characters"));

        verify(companyService, never()).createCompany(any());
    }

    @Test
    void shouldRejectMalformedJson() throws Exception {
        mockMvc.perform(post(COMPANIES_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));

        verify(companyService, never()).createCompany(any());
    }

    @Test
    void shouldHideInternalDetailsOnUnexpectedError() throws Exception {
        when(companyService.createCompany(any()))
                .thenThrow(new IllegalStateException("connection to db-host:5432 refused"));

        mockMvc.perform(post(COMPANIES_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "ABC Trading (Pvt) Ltd"}
                                """))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.detail").value("An unexpected error occurred."))
                .andExpect(content().string(not(containsString("db-host"))));
    }
}
