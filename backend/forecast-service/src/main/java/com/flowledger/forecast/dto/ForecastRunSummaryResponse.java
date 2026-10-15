package com.flowledger.forecast.dto;

import com.flowledger.forecast.calculation.InflowEstimateStatus;
import com.flowledger.forecast.entity.DataQuality;
import com.flowledger.forecast.entity.ForecastRun;
import com.flowledger.forecast.entity.RunStatus;
import com.flowledger.forecast.entity.TriggerType;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A forecast run without its days. {@code openingBalanceAsOf} is the time of the confirmed bank
 * balance the forecast starts from; {@code warnings} label stale or incomplete inputs.
 */
public record ForecastRunSummaryResponse(
        UUID id,
        UUID companyId,
        UUID bankAccountId,
        RunStatus runStatus,
        TriggerType triggerType,
        Instant asOfAt,
        LocalDate horizonStartDate,
        LocalDate horizonEndDate,
        BigDecimal openingBalance,
        Instant openingBalanceAsOf,
        String currency,
        boolean balanceStale,
        InflowEstimateStatus inflowEstimateStatus,
        DataQuality dataQuality,
        List<ForecastWarning> warnings,
        String modelVersion,
        Map<String, Object> config,
        UUID triggeredBy,
        Instant createdAt,
        Instant completedAt
) {

    public static ForecastRunSummaryResponse from(ForecastRun run) {
        return new ForecastRunSummaryResponse(
                run.getId(),
                run.getCompanyId(),
                run.getBankAccountId(),
                run.getRunStatus(),
                run.getTriggerType(),
                run.getAsOfAt(),
                run.getHorizonStartDate(),
                run.getHorizonEndDate(),
                run.getOpeningBalanceAmount(),
                run.getOpeningBalanceAsOf(),
                run.getCurrency(),
                run.isBalanceStale(),
                run.getInflowEstimateStatus(),
                run.getDataQuality(),
                ForecastWarning.forRun(run),
                run.getModelVersion(),
                run.getConfigSnapshot(),
                run.getTriggeredBy(),
                run.getCreatedAt(),
                run.getCompletedAt()
        );
    }
}
