package com.flowledger.forecast.repository;

import com.flowledger.forecast.entity.ForecastRunCheque;
import org.springframework.data.repository.Repository;

import java.util.List;
import java.util.UUID;

// Read-only; callers load the run with its company first.
public interface ForecastRunChequeRepository extends Repository<ForecastRunCheque, ForecastRunCheque.Key> {

    List<ForecastRunCheque> findByForecastRunIdOrderByExpectedClearingDateAscChequeNumberAsc(UUID forecastRunId);
}
