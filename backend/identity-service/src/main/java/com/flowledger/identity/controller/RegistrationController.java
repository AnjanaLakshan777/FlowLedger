package com.flowledger.identity.controller;

import com.flowledger.identity.dto.RegisterUserRequest;
import com.flowledger.identity.dto.RegistrationResponse;
import com.flowledger.identity.service.UserRegistrationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/identity/registrations")
@RequiredArgsConstructor
public class RegistrationController {

    private final UserRegistrationService registrationService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public RegistrationResponse register(@Valid @RequestBody RegisterUserRequest request) {
        return registrationService.register(request);
    }
}
