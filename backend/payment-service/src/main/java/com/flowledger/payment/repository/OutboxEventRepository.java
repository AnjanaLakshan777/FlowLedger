package com.flowledger.payment.repository;

import com.flowledger.payment.entity.OutboxEvent;
import org.springframework.data.repository.Repository;

import java.util.List;
import java.util.UUID;

public interface OutboxEventRepository extends Repository<OutboxEvent, UUID> {

    OutboxEvent save(OutboxEvent event);

    List<OutboxEvent> findByAggregateIdOrderByCreatedAtAsc(UUID aggregateId);
}
