package com.flowledger.payment.repository;

import com.flowledger.payment.entity.PaymentAuditEvent;
import org.springframework.data.repository.Repository;

import java.util.List;
import java.util.UUID;

// Append-only: deliberately no update or delete methods.
public interface PaymentAuditEventRepository extends Repository<PaymentAuditEvent, UUID> {

    PaymentAuditEvent save(PaymentAuditEvent event);

    List<PaymentAuditEvent> findByCompanyIdAndChequeIdOrderByOccurredAtAsc(UUID companyId, UUID chequeId);
}
