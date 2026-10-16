package com.flowledger.identity.dto;

import java.time.Instant;
import java.util.UUID;

// One access change for Users & Settings. field is set for company edits only; from is null when
// something was created.
public record AccessChangeResponse(
        UUID id,
        Instant occurredAt,
        String action,
        UUID actorUserId,
        String actorName,
        UUID targetUserId,
        String targetName,
        String field,
        String from,
        String to
) {
}
