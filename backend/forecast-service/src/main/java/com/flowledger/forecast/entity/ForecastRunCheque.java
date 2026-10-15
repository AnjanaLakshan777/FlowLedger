package com.flowledger.forecast.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

// A cheque as it was when the run used it, so later edits in payment-service never rewrite history.
@Entity
@Immutable
@IdClass(ForecastRunCheque.Key.class)
@Table(name = "forecast_run_cheques")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // Required by JPA
public class ForecastRunCheque {

    @Id
    @Column(name = "forecast_run_id", nullable = false, updatable = false)
    private UUID forecastRunId;

    @Id
    @Column(name = "cheque_id", nullable = false, updatable = false)
    private UUID chequeId;

    @Column(name = "cheque_number", nullable = false, length = 40, updatable = false)
    private String chequeNumber;

    @Column(name = "amount", nullable = false, precision = 19, scale = 2, updatable = false)
    private BigDecimal amount;

    @Column(name = "expected_clearing_date", nullable = false, updatable = false)
    private LocalDate expectedClearingDate;

    @Column(name = "status_at_run", nullable = false, length = 16, updatable = false)
    private String statusAtRun;

    @Column(name = "source_version", nullable = false, updatable = false)
    private int sourceVersion;

    public record Key(UUID forecastRunId, UUID chequeId) implements Serializable {
    }
}
