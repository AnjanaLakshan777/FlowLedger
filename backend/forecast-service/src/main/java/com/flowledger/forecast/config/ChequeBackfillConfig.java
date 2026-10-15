package com.flowledger.forecast.config;

import com.flowledger.forecast.backfill.ChequeBackfillProperties;
import com.flowledger.forecast.verification.ChequeVerificationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({ChequeBackfillProperties.class, ChequeVerificationProperties.class})
public class ChequeBackfillConfig {
}
