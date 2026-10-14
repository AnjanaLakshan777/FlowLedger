package com.flowledger.identity.service;

import com.flowledger.identity.dto.LoginRequest;
import com.flowledger.identity.dto.LoginResponse;
import com.flowledger.identity.entity.AccountStatus;
import com.flowledger.identity.entity.User;
import com.flowledger.identity.exception.InvalidCredentialsException;
import com.flowledger.identity.repository.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class AuthenticationService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AccessTokenService accessTokenService;

    // Compared against when the email is unknown, so that request takes as long as a wrong
    // password and response timing does not reveal which emails have accounts.
    private final String unknownUserPasswordHash;

    public AuthenticationService(UserRepository userRepository,
                                 PasswordEncoder passwordEncoder,
                                 AccessTokenService accessTokenService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.accessTokenService = accessTokenService;
        this.unknownUserPasswordHash = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    @Transactional(readOnly = true)
    public LoginResponse login(LoginRequest request) {
        User user = userRepository.findByEmailIgnoreCase(request.email()).orElse(null);

        String passwordHash = user != null ? user.getPasswordHash() : unknownUserPasswordHash;
        boolean passwordMatches = passwordEncoder.matches(request.password(), passwordHash);

        // PENDING, LOCKED and DISABLED accounts get the same response as a wrong password,
        // so the endpoint does not disclose whether an account exists or what state it is in.
        if (user == null || !passwordMatches || user.getAccountStatus() != AccountStatus.ACTIVE) {
            throw new InvalidCredentialsException();
        }
        return accessTokenService.issueAccessToken(user);
    }
}
