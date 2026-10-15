package com.flowledger.payment.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InternalApiSecretTest {

    private static final String SECRET = "a".repeat(32);
    private static final String IDENTITY_SECRET = "b".repeat(32);

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   "})
    void shouldDisableTheInternalApiWithoutASecret(String secret) throws Exception {
        String checked = SecurityConfig.checkedInternalSecret(secret, IDENTITY_SECRET);

        assertThat(checked).isNull();
        assertThat(filter(checked, SECRET).getStatus()).isEqualTo(401);
        assertThat(filter(checked, "").getStatus()).isEqualTo(401);
    }

    @Test
    void shouldRefuseToStartWithAShortSecret() {
        assertThatThrownBy(() -> SecurityConfig.checkedInternalSecret("a".repeat(31), IDENTITY_SECRET))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining("aaaa");
    }

    @Test
    void shouldRefuseToStartWithTheIdentityServiceSecret() {
        assertThatThrownBy(() -> SecurityConfig.checkedInternalSecret(IDENTITY_SECRET, IDENTITY_SECRET))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must differ");
    }

    @Test
    void shouldAuthenticateOnlyTheExactKeyForTheRequest() throws Exception {
        String checked = SecurityConfig.checkedInternalSecret(SECRET, IDENTITY_SECRET);

        MockFilterChain chain = new MockFilterChain();
        MockHttpServletResponse accepted = filter(checked, SECRET, chain);
        assertThat(accepted.getStatus()).isEqualTo(200);
        assertThat(chain.getRequest()).isNotNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();

        assertThat(filter(checked, SECRET + " ").getStatus()).isEqualTo(401);
        assertThat(filter(checked, SECRET.toUpperCase()).getStatus()).isEqualTo(401);
        assertThat(filter(checked, null).getStatus()).isEqualTo(401);
    }

    private static MockHttpServletResponse filter(String secret, String header) throws Exception {
        return filter(secret, header, new MockFilterChain());
    }

    private static MockHttpServletResponse filter(String secret, String header, MockFilterChain chain)
            throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/internal/v1/payment/cheques");
        if (header != null) {
            request.addHeader(InternalApiKeyFilter.HEADER_NAME, header);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        new InternalApiKeyFilter(secret).doFilter(request, response, chain);
        return response;
    }
}
