package com.flowledger.forecast.calculation;

public enum InflowEstimateStatus {
    ESTIMATED,
    // Too few statement-covered days to estimate; inflows are left out, never assumed to be zero.
    INSUFFICIENT_HISTORY
}
