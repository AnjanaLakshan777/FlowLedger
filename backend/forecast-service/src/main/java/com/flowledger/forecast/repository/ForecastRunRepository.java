package com.flowledger.forecast.repository;

import com.flowledger.forecast.entity.ForecastRun;
import org.springframework.data.repository.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

// Read-only and scoped by company, so one company's run IDs never resolve for another.
public interface ForecastRunRepository extends Repository<ForecastRun, UUID> {

    Optional<ForecastRun> findByCompanyIdAndId(UUID companyId, UUID id);

    Optional<ForecastRun> findByCompanyIdAndInputSnapshotHash(UUID companyId, String inputSnapshotHash);

    List<ForecastRun> findByCompanyIdOrderByCreatedAtDesc(UUID companyId);
}
