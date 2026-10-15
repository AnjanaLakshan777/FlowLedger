package com.flowledger.identity.service;

import com.flowledger.identity.dto.CurrentUserResponse;
import com.flowledger.identity.entity.AccountStatus;
import com.flowledger.identity.entity.User;
import com.flowledger.identity.exception.OperationNotPermittedException;
import com.flowledger.identity.repository.CompanyMembershipRepository;
import com.flowledger.identity.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Who the signed-in user is and which companies they can work in, so a client can choose the
 * company and show the right pages. Statuses are read at request time, like every access check.
 */
@Service
@RequiredArgsConstructor
public class CurrentUserService {

    private final UserRepository userRepository;
    private final CompanyMembershipRepository membershipRepository;

    @Transactional(readOnly = true)
    public CurrentUserResponse getCurrentUser(UUID userId) {
        User user = userRepository.findById(userId)
                .filter(found -> found.getAccountStatus() == AccountStatus.ACTIVE)
                .orElseThrow(() -> new OperationNotPermittedException("Your account is not active."));
        return CurrentUserResponse.from(user, membershipRepository.findActiveForUser(userId));
    }
}
