package com.flowledger.bankintegration.repository;

import com.flowledger.bankintegration.entity.OutboxEvent;
import org.springframework.data.repository.Repository;

import java.util.List;
import java.util.UUID;

public interface OutboxEventRepository extends Repository<OutboxEvent, UUID> {

    OutboxEvent save(OutboxEvent event);

    List<OutboxEvent> findByAggregateIdOrderByCreatedAtAsc(UUID aggregateId);
}
