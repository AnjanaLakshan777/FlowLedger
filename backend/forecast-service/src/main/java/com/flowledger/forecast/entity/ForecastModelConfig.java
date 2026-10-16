package com.flowledger.forecast.entity;

import com.flowledger.forecast.calculation.ForecastCalculator;
import com.flowledger.forecast.calculation.ForecastParameters;
import com.flowledger.forecast.calculation.PreviousYearPatternStrategy;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Generated;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * A company's forecast settings. Versions are kept, and every run stores a copy of the version it
 * used. There is no API to change settings yet; the first run of a company creates version 1 with
 * the PREVIOUS_YEAR_PATTERN defaults. Companies that had a WEEKDAY_AVERAGE version 1 were moved to a
 * new PREVIOUS_YEAR_PATTERN version by migration V6; their old version and its runs are unchanged.
 */
@Entity
@Table(name = "forecast_model_configs")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // Required by JPA
public class ForecastModelConfig {

    /** Method of WEEKDAY_AVERAGE configurations. */
    public static final String METHOD_CODE = "HISTORICAL_BANK_CASH_FLOW_PATTERN";
    /** Method of PREVIOUS_YEAR_PATTERN configurations (Option B). */
    public static final String PREVIOUS_YEAR_METHOD_CODE = "PREVIOUS_YEAR_CORRESPONDING_WEEK_PATTERN";

    @Id
    @Generated
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "company_id", nullable = false, updatable = false)
    private UUID companyId;

    @Column(name = "version", nullable = false, updatable = false)
    private int version;

    @Column(name = "is_active", nullable = false)
    private boolean active;

    @Column(name = "horizon_days", nullable = false, updatable = false)
    private short horizonDays;

    @Column(name = "minimum_balance", nullable = false, precision = 19, scale = 2, updatable = false)
    private BigDecimal minimumBalance;

    @Column(name = "method_code", nullable = false, length = 80, updatable = false)
    private String methodCode;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "parameters", nullable = false, updatable = false)
    private ForecastParameters parameters;

    @Column(name = "created_by", nullable = false, updatable = false)
    private UUID createdBy;

    @Generated
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public static ForecastModelConfig defaults(UUID companyId, UUID createdBy) {
        ForecastModelConfig config = new ForecastModelConfig();
        config.companyId = companyId;
        config.version = 1;
        config.active = true;
        config.horizonDays = (short) ForecastCalculator.HORIZON_DAYS;
        config.minimumBalance = BigDecimal.ZERO.setScale(2);
        config.methodCode = PREVIOUS_YEAR_METHOD_CODE;
        config.parameters = ForecastParameters.PREVIOUS_YEAR_DEFAULTS;
        config.createdBy = createdBy;
        return config;
    }

    // The copy stored with each run, so later setting changes never alter a saved forecast.
    public Map<String, Object> snapshot() {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("configId", id.toString());
        snapshot.put("version", version);
        snapshot.put("horizonDays", horizonDays);
        snapshot.put("minimumBalance", minimumBalance.toPlainString());
        snapshot.put("methodCode", methodCode);
        snapshot.put("strategy", parameters.strategy());
        snapshot.put("lookbackWeeks", parameters.lookbackWeeks());
        snapshot.put("minimumSamplesPerWeekday", parameters.minimumSamplesPerWeekday());
        snapshot.put("balanceStaleAfterDays", parameters.balanceStaleAfterDays());
        // Only in configurations that have them, so WEEKDAY_AVERAGE snapshots keep their shape.
        if (parameters.cashReceiptLagDays() != null) {
            snapshot.put("cashReceiptLagDays", parameters.cashReceiptLagDays());
            snapshot.put("cardReceiptLagDays", parameters.cardReceiptLagDays());
            snapshot.put("growthRatioWarningLow", parameters.growthRatioWarningLow().toPlainString());
            snapshot.put("growthRatioWarningHigh", parameters.growthRatioWarningHigh().toPlainString());
            snapshot.put("referenceOffsetDays", PreviousYearPatternStrategy.REFERENCE_OFFSET_DAYS);
        }
        return snapshot;
    }
}
