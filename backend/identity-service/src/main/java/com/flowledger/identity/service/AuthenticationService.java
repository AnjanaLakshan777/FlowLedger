package com.flowledger.identity.service;

import com.flowledger.identity.dto.LoginRequest;
import com.flowledger.identity.dto.LoginResponse;
import com.flowledger.identity.entity.AccountStatus;
import com.flowledger.identity.entity.User;
import com.flowledger.identity.dto.PasswordChangeRequest;
import com.flowledger.identity.exception.InvalidCredentialsException;
import com.flowledger.identity.exception.InvalidNewPasswordException;
import com.flowledger.identity.exception.PasswordChangeRequiredException;
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
        User user = authenticate(request.email(), request.password());
        // Checked only after the password matched, so it tells nothing to someone without it.
        if (user.isPasswordChangeRequired()) {
            throw new PasswordChangeRequiredException();
        }
        return accessTokenService.issueAccessToken(user);
    }

    /**
     * Replaces the password of an account that proves its current one, and signs it in. A new
     * account does this on its first sign-in, which replaces the admin's temporary password.
     */
    @Transactional
    public LoginResponse changePassword(PasswordChangeRequest request) {
        User user = authenticate(request.email(), request.currentPassword());
        if (request.newPassword().equals(request.currentPassword())) {
            throw new InvalidNewPasswordException("newPassword must be different from the current password");
        }
        user.changePassword(passwordEncoder.encode(request.newPassword()));
        userRepository.saveAndFlush(user);
        return accessTokenService.issueAccessToken(user);
    }

    private User authenticate(String email, String password) {
        User user = userRepository.findByEmailIgnoreCase(email).orElse(null);

        String passwordHash = user != null ? user.getPasswordHash() : unknownUserPasswordHash;
        boolean passwordMatches = passwordEncoder.matches(password, passwordHash);

        // PENDING, LOCKED and DISABLED accounts get the same response as a wrong password,
        // so the endpoint does not disclose whether an account exists or what state it is in.
        if (user == null || !passwordMatches || user.getAccountStatus() != AccountStatus.ACTIVE) {
            throw new InvalidCredentialsException();
        }
        return user;
    }
}
