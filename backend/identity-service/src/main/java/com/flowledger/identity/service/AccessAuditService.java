package com.flowledger.identity.service;

import com.flowledger.identity.dto.AccessChangeResponse;
import com.flowledger.identity.entity.IdentityAuditEvent;
import com.flowledger.identity.entity.User;
import com.flowledger.identity.repository.IdentityAuditEventRepository;
import com.flowledger.identity.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Records and lists a company's access changes. Callers record inside their own transaction, so
 * an event exists exactly when its change was committed.
 */
@Service
@RequiredArgsConstructor
public class AccessAuditService {

    public static final String MEMBERSHIP_CREATED = "MEMBERSHIP_CREATED";
    public static final String MEMBERSHIP_ROLE_CHANGED = "MEMBERSHIP_ROLE_CHANGED";
    public static final String MEMBERSHIP_STATUS_CHANGED = "MEMBERSHIP_STATUS_CHANGED";
    public static final String COMPANY_UPDATED = "COMPANY_UPDATED";

    private final IdentityAuditEventRepository auditEventRepository;
    private final UserRepository userRepository;
    private final AdminAuthorizationService adminAuthorizationService;

    public void record(UUID companyId, UUID actorId, String action, String targetType, UUID targetId,
                       Map<String, Object> details) {
        auditEventRepository.save(new IdentityAuditEvent(companyId, actorId, action, targetType, targetId, details));
    }

    @Transactional(readOnly = true)
    public List<AccessChangeResponse> listAccessChanges(UUID actorId, UUID companyId) {
        adminAuthorizationService.requireCompanyAdministration(actorId, companyId);
        List<IdentityAuditEvent> events = auditEventRepository.findTop200ByCompanyIdOrderByOccurredAtDesc(companyId);

        // Names are read now, so a renamed user shows their current name.
        Set<UUID> userIds = new HashSet<>();
        for (IdentityAuditEvent event : events) {
            if (event.getActorUserId() != null) {
                userIds.add(event.getActorUserId());
            }
            UUID target = targetUserId(event);
            if (target != null) {
                userIds.add(target);
            }
        }
        Map<UUID, String> names = userRepository.findAllById(userIds).stream()
                .collect(Collectors.toMap(User::getId, User::getFullName));
        Function<UUID, String> name = id -> id == null ? null : names.get(id);

        return events.stream()
                .map(event -> {
                    UUID target = targetUserId(event);
                    return new AccessChangeResponse(
                            event.getId(),
                            event.getOccurredAt(),
                            event.getAction(),
                            event.getActorUserId(),
                            name.apply(event.getActorUserId()),
                            target,
                            name.apply(target),
                            text(event, "field"),
                            text(event, "from"),
                            text(event, "to"));
                })
                .toList();
    }

    private static UUID targetUserId(IdentityAuditEvent event) {
        String userId = text(event, "userId");
        return userId == null ? null : UUID.fromString(userId);
    }

    private static String text(IdentityAuditEvent event, String key) {
        Object value = event.getDetails().get(key);
        return value == null ? null : value.toString();
    }
}
