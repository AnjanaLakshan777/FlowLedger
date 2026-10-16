package com.flowledger.identity.repository;

import com.flowledger.identity.entity.IdentityAuditEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface IdentityAuditEventRepository extends JpaRepository<IdentityAuditEvent, UUID> {

    // Newest first, capped so the screen never loads an unbounded history.
    List<IdentityAuditEvent> findTop200ByCompanyIdOrderByOccurredAtDesc(UUID companyId);
}
