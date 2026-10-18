package com.flowledger.payment.service;

import com.flowledger.payment.client.CurrentAccountClient;
import com.flowledger.payment.dto.ChequeRequest;
import com.flowledger.payment.entity.Cheque;
import com.flowledger.payment.entity.ChequeCategory;
import com.flowledger.payment.exception.DuplicateChequeNumberException;
import com.flowledger.payment.exception.InvalidChequeException;
import com.flowledger.payment.repository.ChequeRepository;
import com.flowledger.payment.repository.OutboxEventRepository;
import com.flowledger.payment.repository.PaymentAuditEventRepository;
import com.flowledger.payment.security.CompanyAccessVerifier;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.PlatformTransactionManager;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChequeServiceTest {

    // 2026-10-15 19:00 UTC is already 2026-10-16 00:30 in Colombo.
    private static final Instant COLOMBO_JUST_AFTER_MIDNIGHT = Instant.parse("2026-10-15T19:00:00Z");

    private final ChequeRepository chequeRepository = mock(ChequeRepository.class);
    private final PaymentAuditEventRepository auditRepository = mock(PaymentAuditEventRepository.class);
    private final OutboxEventRepository outboxRepository = mock(OutboxEventRepository.class);
    private final CompanyAccessVerifier companyAccessVerifier = mock(CompanyAccessVerifier.class);
    private final CurrentAccountClient currentAccountClient = mock(CurrentAccountClient.class);

    private final ChequeService service = new ChequeService(chequeRepository, auditRepository, outboxRepository,
            companyAccessVerifier, currentAccountClient,
            Clock.fixed(COLOMBO_JUST_AFTER_MIDNIGHT, ZoneId.of("Asia/Colombo")),
            mock(PlatformTransactionManager.class));

    // "Today" is the business date in Asia/Colombo: on the 16th there, a clearing date of the 16th
    // is no longer after today, even though it is still the 15th in UTC.
    @Test
    void shouldJudgeClearingDateAgainstTodayInColombo() {
        assertThatThrownBy(() -> service.addCheque(user(), UUID.randomUUID(), request(LocalDate.of(2026, 10, 16))))
                .isInstanceOf(InvalidChequeException.class);
        verify(currentAccountClient, never()).findActiveCurrentAccount(any(), any());
    }

    // The duplicate check passed, but a concurrent request recorded the same number first.
    @Test
    void shouldReportDuplicateWhenUniqueConstraintRejectsConcurrentInsert() {
        UUID companyId = UUID.randomUUID();
        when(currentAccountClient.findActiveCurrentAccount(any(), any())).thenReturn(UUID.randomUUID());
        when(chequeRepository.saveAndFlush(any(Cheque.class)))
                .thenThrow(new DataIntegrityViolationException("uq_cheques_company_account_number"));

        assertThatThrownBy(() -> service.addCheque(user(), companyId, request(LocalDate.of(2026, 10, 17))))
                .isInstanceOf(DuplicateChequeNumberException.class);
        verify(auditRepository, never()).save(any());
        verify(outboxRepository, never()).save(any());
    }

    private static ChequeRequest request(LocalDate clearingDate) {
        return new ChequeRequest("000123", "Lanka Tiles", ChequeCategory.TAX, null, new BigDecimal("1000.00"),
                LocalDate.of(2026, 10, 10), clearingDate, null, null);
    }

    private static Jwt user() {
        return Jwt.withTokenValue("user-access-token").header("alg", "HS256")
                .subject(UUID.randomUUID().toString()).build();
    }
}
