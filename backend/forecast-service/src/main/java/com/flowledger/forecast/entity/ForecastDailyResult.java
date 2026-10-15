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

/**
 * One forecast day of a run. Contains forecasts only, never actual bank values. The inflow
 * amounts are null when the run could not estimate inflows.
 */
@Entity
@Immutable
@IdClass(ForecastDailyResult.Key.class)
@Table(name = "forecast_daily_results")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // Required by JPA
public class ForecastDailyResult {

    @Id
    @Column(name = "forecast_run_id", nullable = false, updatable = false)
    private UUID forecastRunId;

    @Id
    @Column(name = "forecast_date", nullable = false, updatable = false)
    private LocalDate forecastDate;

    @Column(name = "expected_cash_sales_deposits_amount", precision = 19, scale = 2, updatable = false)
    private BigDecimal expectedCashSalesDeposits;

    @Column(name = "expected_card_sales_settlements_amount", precision = 19, scale = 2, updatable = false)
    private BigDecimal expectedCardSalesSettlements;

    @Column(name = "expected_sales_inflows_amount", precision = 19, scale = 2, updatable = false)
    private BigDecimal expectedSalesInflows;

    @Column(name = "expected_cheque_outflows_amount", nullable = false, precision = 19, scale = 2, updatable = false)
    private BigDecimal expectedChequeOutflows;

    @Column(name = "expected_cheque_count", nullable = false, updatable = false)
    private int expectedChequeCount;

    @Column(name = "opening_balance_amount", nullable = false, precision = 19, scale = 2, updatable = false)
    private BigDecimal openingBalance;

    @Column(name = "closing_balance_amount", nullable = false, precision = 19, scale = 2, updatable = false)
    private BigDecimal closingBalance;

    @Column(name = "is_cash_shortage", nullable = false, updatable = false)
    private boolean cashShortage;

    public record Key(UUID forecastRunId, LocalDate forecastDate) implements Serializable {
    }
}
