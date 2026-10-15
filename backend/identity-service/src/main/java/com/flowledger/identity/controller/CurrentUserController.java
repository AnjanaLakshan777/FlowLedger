package com.flowledger.identity.controller;

import com.flowledger.identity.dto.CurrentUserResponse;
import com.flowledger.identity.service.CurrentUserService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/identity/me")
@RequiredArgsConstructor
public class CurrentUserController {

    private final CurrentUserService currentUserService;

    @GetMapping
    public CurrentUserResponse getCurrentUser(@AuthenticationPrincipal Jwt user) {
        return currentUserService.getCurrentUser(UUID.fromString(user.getSubject()));
    }
}
