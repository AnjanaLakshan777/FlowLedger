package com.flowledger.bankintegration.repository;

import com.flowledger.bankintegration.entity.BankIntegrationAuditEvent;
import org.springframework.data.repository.Repository;

import java.util.List;
import java.util.UUID;

// Append-only: deliberately no update or delete methods.
public interface BankIntegrationAuditEventRepository extends Repository<BankIntegrationAuditEvent, UUID> {

    BankIntegrationAuditEvent save(BankIntegrationAuditEvent event);

    List<BankIntegrationAuditEvent> findByCompanyIdAndEntityIdOrderByOccurredAtAsc(UUID companyId, UUID entityId);
}
