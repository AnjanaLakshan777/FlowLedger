package com.flowledger.identity.dto;

public record LoginResponse(String accessToken, String tokenType, long expiresIn) {
}
