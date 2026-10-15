package com.flowledger.forecast.backfill;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Starts or resumes the cheque backfill in the background after startup when
 * CHEQUE_BACKFILL_ON_STARTUP=true. Startup does not wait for it, and a failing backfill does not
 * stop the service.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "flowledger.cheque-backfill.run-on-startup", havingValue = "true")
class ChequeBackfillStartupRunner implements ApplicationRunner {

    private final ChequeBackfill backfill;

    @Override
    public void run(ApplicationArguments args) {
        Thread thread = new Thread(() -> {
            try {
                ChequeBackfill.Result result = backfill.run();
                log.info("Cheque backfill run {} ended: {}", result.runId(), result.outcome());
            } catch (RuntimeException e) {
                log.error("Cheque backfill could not run: {}", e instanceof IllegalStateException
                        ? e.getMessage() : e.getClass().getSimpleName());
            }
        }, "cheque-backfill");
        thread.setDaemon(true);
        thread.start();
    }
}
