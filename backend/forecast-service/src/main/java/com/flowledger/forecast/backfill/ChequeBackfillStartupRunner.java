package com.flowledger.forecast.backfill;

import com.flowledger.forecast.verification.ChequeProjectionVerifier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Starts or resumes the cheque backfill in the background after startup when
 * CHEQUE_BACKFILL_ON_STARTUP=true, and verifies the projection once the run has completed. Startup
 * does not wait for either, and a failure does not stop the service.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "flowledger.cheque-backfill.run-on-startup", havingValue = "true")
class ChequeBackfillStartupRunner implements ApplicationRunner {

    private final ChequeBackfill backfill;
    private final ChequeProjectionVerifier verifier;

    @Override
    public void run(ApplicationArguments args) {
        Thread thread = new Thread(() -> {
            try {
                ChequeBackfill.Result result = backfill.run();
                log.info("Cheque backfill run {} ended: {}", result.runId(), result.outcome());
                if (result.outcome() == ChequeBackfill.Outcome.COMPLETED) {
                    verifier.verifyAll();
                }
            } catch (RuntimeException e) {
                log.error("Cheque backfill or verification could not run: {}", e instanceof IllegalStateException
                        ? e.getMessage() : e.getClass().getSimpleName());
            }
        }, "cheque-backfill");
        thread.setDaemon(true);
        thread.start();
    }
}
