package com.flowledger.forecast.calculation;

import java.math.BigDecimal;

// Expected sales money reaching the bank on one day, by bank transaction category.
public record ExpectedInflow(BigDecimal cashSalesDeposits, BigDecimal cardSalesSettlements) {

    public BigDecimal total() {
        return cashSalesDeposits.add(cardSalesSettlements);
    }
}
