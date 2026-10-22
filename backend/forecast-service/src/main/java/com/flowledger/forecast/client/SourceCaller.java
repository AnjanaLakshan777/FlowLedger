package com.flowledger.forecast.client;

import org.springframework.security.oauth2.jwt.Jwt;

import java.util.Objects;

/**
 * Who reads forecast inputs from the other services: a signed-in user, whose own token is sent to
 * the public API so that service applies its own authorization, or forecast-service itself, for the
 * daily scheduled forecast, which uses the internal API with its internal API key.
 *
 * @param user the user's token, or null for the service itself
 */
public record SourceCaller(Jwt user) {

    public static final SourceCaller SERVICE = new SourceCaller(null);

    public static SourceCaller user(Jwt user) {
        return new SourceCaller(Objects.requireNonNull(user, "user"));
    }

    public boolean isService() {
        return user == null;
    }

    String bearer() {
        return "Bearer " + user.getTokenValue();
    }
}
