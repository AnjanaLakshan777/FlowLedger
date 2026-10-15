package com.flowledger.forecast.entity;

import com.flowledger.forecast.calculation.InflowEstimateStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

/**
 * A saved forecast. Runs are inserted complete by ForecastRunStore and never change; a later
 * forecast is a new run.
 */
@Entity
@Immutable
@Table(name = "forecast_runs")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // Required by JPA
public class ForecastRun {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "company_id", nullable = false, updatable = false)
    private UUID companyId;

    @Column(name = "config_id", nullable = false, updatable = false)
    private UUID configId;

    @Column(name = "bank_account_id", nullable = false, updatable = false)
    private UUID bankAccountId;

    @Enumerated(EnumType.STRING)
    @Column(name = "run_status", nullable = false, length = 16, updatable = false)
    private RunStatus runStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "trigger_type", nullable = false, length = 16, updatable = false)
    private TriggerType triggerType;

    @Column(name = "as_of_at", nullable = false, updatable = false)
    private Instant asOfAt;

    @Column(name = "horizon_start_date", nullable = false, updatable = false)
    private LocalDate horizonStartDate;

    @Column(name = "horizon_end_date", nullable = false, updatable = false)
    private LocalDate horizonEndDate;

    @Column(name = "opening_balance_amount", nullable = false, precision = 19, scale = 2, updatable = false)
    private BigDecimal openingBalanceAmount;

    @Column(name = "opening_balance_as_of", nullable = false, updatable = false)
    private Instant openingBalanceAsOf;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "currency", nullable = false, length = 3, updatable = false)
    private String currency;

    @Column(name = "balance_stale", nullable = false, updatable = false)
    private boolean balanceStale;

    @Enumerated(EnumType.STRING)
    @Column(name = "inflow_estimate_status", nullable = false, length = 24, updatable = false)
    private InflowEstimateStatus inflowEstimateStatus;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "data_quality", nullable = false, updatable = false)
    private DataQuality dataQuality;

    @Column(name = "model_version", nullable = false, length = 40, updatable = false)
    private String modelVersion;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "config_snapshot", nullable = false, updatable = false)
    private Map<String, Object> configSnapshot;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "input_snapshot_hash", length = 64, updatable = false)
    private String inputSnapshotHash;

    @Column(name = "triggered_by", updatable = false)
    private UUID triggeredBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "completed_at", updatable = false)
    private Instant completedAt;

    @Column(name = "error_summary", columnDefinition = "text", updatable = false)
    private String errorSummary;
}
