package com.flowledger.forecast.entity;

import com.flowledger.forecast.calculation.ForecastCalculator;
import com.flowledger.forecast.calculation.ForecastParameters;
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
 * used. There is no API to change settings yet; the first run creates version 1 with the defaults.
 */
@Entity
@Table(name = "forecast_model_configs")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // Required by JPA
public class ForecastModelConfig {

    public static final String METHOD_CODE = "HISTORICAL_BANK_CASH_FLOW_PATTERN";

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
        config.methodCode = METHOD_CODE;
        config.parameters = ForecastParameters.DEFAULTS;
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
        return snapshot;
    }
}
