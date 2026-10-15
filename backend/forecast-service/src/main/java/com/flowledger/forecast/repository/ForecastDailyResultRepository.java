package com.flowledger.forecast.repository;

import com.flowledger.forecast.entity.ForecastDailyResult;
import org.springframework.data.repository.Repository;

import java.util.List;
import java.util.UUID;

// Read-only; callers load the run with its company first.
public interface ForecastDailyResultRepository extends Repository<ForecastDailyResult, ForecastDailyResult.Key> {

    List<ForecastDailyResult> findByForecastRunIdOrderByForecastDate(UUID forecastRunId);
}
