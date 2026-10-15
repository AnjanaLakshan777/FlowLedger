package com.flowledger.identity.controller;

import com.flowledger.identity.dto.CompanyResponse;
import com.flowledger.identity.dto.CreateCompanyRequest;
import com.flowledger.identity.entity.CompanyStatus;
import com.flowledger.identity.exception.GlobalExceptionHandler;
import com.flowledger.identity.service.CompanyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

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

/**
 * Request handling of the company endpoint. The security chain is not part of this standalone
 * setup; who may create companies is tested in CompanyControllerIntegrationTest.
 */
class CompanyControllerTest {

    private static final String COMPANIES_URL = "/api/v1/identity/companies";
    private static final UUID ACTOR_ID = UUID.randomUUID();

    private CompanyService companyService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        companyService = mock(CompanyService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new CompanyController(companyService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(authenticatedUser())
                .build();
    }

    // Supplies the access token the security chain would otherwise resolve.
    private static HandlerMethodArgumentResolver authenticatedUser() {
        return new HandlerMethodArgumentResolver() {
            @Override
            public boolean supportsParameter(MethodParameter parameter) {
                return parameter.getParameterType() == Jwt.class;
            }

            @Override
            public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
                                          NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
                return Jwt.withTokenValue("token").header("alg", "HS256").subject(ACTOR_ID.toString()).build();
            }
        };
    }

    @Test
    void shouldCreateCompany() throws Exception {
        UUID id = UUID.randomUUID();
        when(companyService.createCompany(any(), any(CreateCompanyRequest.class))).thenReturn(new CompanyResponse(
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

        verify(companyService).createCompany(ACTOR_ID, new CreateCompanyRequest("ABC Trading (Pvt) Ltd", "PV12345"));
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

        verify(companyService, never()).createCompany(any(), any());
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

        verify(companyService, never()).createCompany(any(), any());
    }

    @Test
    void shouldRejectNameLongerThan200Characters() throws Exception {
        mockMvc.perform(post(COMPANIES_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"" + "A".repeat(201) + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.name").value("name must be at most 200 characters"));

        verify(companyService, never()).createCompany(any(), any());
    }

    @Test
    void shouldRejectRegistrationNumberLongerThan80Characters() throws Exception {
        mockMvc.perform(post(COMPANIES_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"ABC\", \"registrationNumber\": \"" + "P".repeat(81) + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.registrationNumber")
                        .value("registrationNumber must be at most 80 characters"));

        verify(companyService, never()).createCompany(any(), any());
    }

    @Test
    void shouldRejectMalformedJson() throws Exception {
        mockMvc.perform(post(COMPANIES_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));

        verify(companyService, never()).createCompany(any(), any());
    }

    @Test
    void shouldHideInternalDetailsOnUnexpectedError() throws Exception {
        when(companyService.createCompany(any(), any()))
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
