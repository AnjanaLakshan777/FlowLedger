package com.flowledger.forecast.config;

import com.flowledger.forecast.backfill.ChequeBackfillProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(ChequeBackfillProperties.class)
public class ChequeBackfillConfig {
}
