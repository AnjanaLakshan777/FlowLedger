package com.flowledger.forecast.repository;

import com.flowledger.forecast.entity.ForecastModelConfig;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ForecastModelConfigRepository extends JpaRepository<ForecastModelConfig, UUID> {

    Optional<ForecastModelConfig> findByCompanyIdAndActiveTrue(UUID companyId);
}
