package com.flowledger.payment.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;

@Slf4j
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private static final int MIN_INTERNAL_SECRET_LENGTH = 32;

    // Service-to-service API, authenticated by the internal API key alone; user access tokens
    // are not accepted here. Ordered first so the general chain below never handles
    // /internal/** requests. The API gateway does not route /internal/**.
    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public SecurityFilterChain internalApiSecurityFilterChain(
            HttpSecurity http,
            @Value("${flowledger.security.internal-api.secret:}") String internalApiSecret,
            IdentityServiceProperties identityProperties) throws Exception {
        http
                .securityMatcher("/internal/**")
                .csrf(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .addFilterBefore(new InternalApiKeyFilter(checkedInternalSecret(internalApiSecret,
                        identityProperties.internalApiSecret())), AnonymousAuthenticationFilter.class)
                .authorizeHttpRequests(auth -> auth.anyRequest().hasAuthority(InternalApiKeyFilter.AUTHORITY));

        return http.build();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                // Clients send the access token in the Authorization header, never in a cookie, so a
                // cross-site request cannot carry credentials and CSRF tokens add no protection.
                .csrf(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.GET, "/api/v1/payment/health").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()));

        return http.build();
    }

    /**
     * Without PAYMENT_INTERNAL_API_SECRET the service still starts (the public API does not need
     * it) but refuses every internal request. A secret that is set but too short, or the same as
     * IDENTITY_INTERNAL_API_SECRET, stops startup: one leaked key must not open both APIs.
     */
    static String checkedInternalSecret(String secret, String identitySecret) {
        if (secret == null || secret.isBlank()) {
            log.warn("PAYMENT_INTERNAL_API_SECRET is not set; the internal API refuses every request.");
            return null;
        }
        if (secret.length() < MIN_INTERNAL_SECRET_LENGTH) {
            throw new IllegalStateException(
                    "PAYMENT_INTERNAL_API_SECRET must be at least " + MIN_INTERNAL_SECRET_LENGTH + " characters.");
        }
        if (secret.equals(identitySecret)) {
            throw new IllegalStateException(
                    "PAYMENT_INTERNAL_API_SECRET must differ from IDENTITY_INTERNAL_API_SECRET.");
        }
        return secret;
    }
}
