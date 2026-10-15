package com.flowledger.forecast.calculation;

import java.time.LocalDate;
import java.util.List;

/**
 * Estimates future sales inflows from actual bank history. The forecasting rule is not confirmed
 * yet, so the calculation is kept behind this interface and chosen by the model configuration.
 */
public interface InflowForecastStrategy {

    String code();

    InflowEstimate estimate(InflowHistory history, List<LocalDate> forecastDates, ForecastParameters parameters);
}
