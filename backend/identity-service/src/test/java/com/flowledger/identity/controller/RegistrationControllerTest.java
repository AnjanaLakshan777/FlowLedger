package com.flowledger.identity.controller;

import com.flowledger.identity.dto.RegisterUserRequest;
import com.flowledger.identity.dto.RegistrationResponse;
import com.flowledger.identity.entity.AccountStatus;
import com.flowledger.identity.entity.CompanyRole;
import com.flowledger.identity.entity.MembershipStatus;
import com.flowledger.identity.exception.GlobalExceptionHandler;
import com.flowledger.identity.service.UserRegistrationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class RegistrationControllerTest {

    private static final String REGISTRATIONS_URL = "/api/v1/identity/registrations";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final UUID COMPANY_ID = UUID.fromString("7d0f3c3e-2b7a-4c1e-9a55-0f3e8b6a1c42");

    private UserRegistrationService registrationService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        registrationService = mock(UserRegistrationService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new RegistrationController(registrationService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void shouldRegisterUser() throws Exception {
        UUID userId = UUID.randomUUID();
        when(registrationService.register(any(RegisterUserRequest.class))).thenReturn(new RegistrationResponse(
                userId, "nimal@example.com", "Nimal Perera", AccountStatus.PENDING,
                COMPANY_ID, CompanyRole.ACCOUNTANT, MembershipStatus.INVITED,
                Instant.parse("2026-01-01T00:00:00Z")));

        mockMvc.perform(post(REGISTRATIONS_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson(Map.of())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.userId").value(userId.toString()))
                .andExpect(jsonPath("$.email").value("nimal@example.com"))
                .andExpect(jsonPath("$.accountStatus").value("PENDING"))
                .andExpect(jsonPath("$.companyId").value(COMPANY_ID.toString()))
                .andExpect(jsonPath("$.companyRole").value("ACCOUNTANT"))
                .andExpect(jsonPath("$.membershipStatus").value("INVITED"))
                .andExpect(jsonPath("$.password").doesNotExist());

        verify(registrationService).register(new RegisterUserRequest(
                "nimal@example.com", "Nimal Perera", "correct-horse-battery", COMPANY_ID, CompanyRole.ACCOUNTANT));
    }

    static Stream<Arguments> invalidFields() {
        return Stream.of(
                Arguments.of("email", null, "email must not be blank"),
                Arguments.of("email", "not-an-email", "email must be a valid email address"),
                Arguments.of("fullName", "   ", "fullName must not be blank"),
                Arguments.of("fullName", "N".repeat(161), "fullName must be at most 160 characters"),
                Arguments.of("password", " ".repeat(12), "password must not be blank"),
                Arguments.of("password", "too-short", "password must be between 12 and 72 characters"),
                Arguments.of("password", "p".repeat(73), "password must be between 12 and 72 characters"),
                Arguments.of("companyId", null, "companyId is required"),
                Arguments.of("companyRole", null, "companyRole is required")
        );
    }

    @ParameterizedTest(name = "{0} = {1}")
    @MethodSource("invalidFields")
    void shouldRejectInvalidField(String field, Object value, String message) throws Exception {
        Map<String, Object> overrides = new LinkedHashMap<>();
        overrides.put(field, value);

        mockMvc.perform(post(REGISTRATIONS_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson(overrides)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Validation failed"))
                .andExpect(jsonPath("$.errors." + field).value(message));

        verify(registrationService, never()).register(any());
    }

    @Test
    void shouldRejectPasswordOverBcryptByteLimit() throws Exception {
        // 37 characters, but 74 bytes in UTF-8.
        mockMvc.perform(post(REGISTRATIONS_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson(Map.of("password", "é".repeat(37)))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.passwordWithinByteLimit").value("password must be at most 72 bytes"));

        verify(registrationService, never()).register(any());
    }

    @Test
    void shouldRejectUnknownCompanyRole() throws Exception {
        mockMvc.perform(post(REGISTRATIONS_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson(Map.of("companyRole", "SUPER_ADMIN"))))
                .andExpect(status().isBadRequest());

        verify(registrationService, never()).register(any());
    }

    @Test
    void shouldNotExposePasswordInToString() {
        RegisterUserRequest request = new RegisterUserRequest(
                "nimal@example.com", "Nimal Perera", "correct-horse-battery", COMPANY_ID, CompanyRole.CASHIER);

        assertThat(request.toString()).doesNotContain("correct-horse-battery");
    }

    private static String requestJson(Map<String, Object> overrides) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("email", "nimal@example.com");
        body.put("fullName", "Nimal Perera");
        body.put("password", "correct-horse-battery");
        body.put("companyId", COMPANY_ID.toString());
        body.put("companyRole", "ACCOUNTANT");
        body.putAll(overrides);
        return JSON.writeValueAsString(body);
    }
}
