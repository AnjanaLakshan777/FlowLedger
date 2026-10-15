package com.flowledger.cashmanagement.service;

import com.flowledger.cashmanagement.config.ClockConfig;
import com.flowledger.cashmanagement.dto.CreateDailySaleRequest;
import com.flowledger.cashmanagement.entity.DailySale;
import com.flowledger.cashmanagement.exception.DuplicateSaleDateException;
import com.flowledger.cashmanagement.exception.InvalidDailySaleException;
import com.flowledger.cashmanagement.repository.DailySaleRepository;
import com.flowledger.cashmanagement.security.CompanyAccessVerifier;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DailySaleServiceTest {

    private static final ZoneId COLOMBO = ZoneId.of("Asia/Colombo");

    // 2026-10-15 19:00 UTC is already 2026-10-16 00:30 in Colombo.
    private static final Instant COLOMBO_JUST_AFTER_MIDNIGHT = Instant.parse("2026-10-15T19:00:00Z");

    private final DailySaleRepository repository = mock(DailySaleRepository.class);
    private final CompanyAccessVerifier companyAccessVerifier = mock(CompanyAccessVerifier.class);
    private final PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);

    // "Today" for the future-date rule is the business date in Asia/Colombo (UTC+05:30), not the
    // server's UTC date. A fixed clock pins the hours where the two dates differ.
    @Test
    void shouldAcceptSaleDateThatIsTodayInColomboWhileStillYesterdayInUtc() {
        DailySaleService service = serviceAt(COLOMBO_JUST_AFTER_MIDNIGHT);
        when(repository.saveAndFlush(any(DailySale.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var response = service.createDailySale(user(), UUID.randomUUID(), request(LocalDate.of(2026, 10, 16)));

        assertThat(response.saleDate()).isEqualTo(LocalDate.of(2026, 10, 16));
    }

    @Test
    void shouldRejectSaleDateAfterTodayInColombo() {
        DailySaleService service = serviceAt(COLOMBO_JUST_AFTER_MIDNIGHT);

        assertThatThrownBy(() -> service.createDailySale(user(), UUID.randomUUID(), request(LocalDate.of(2026, 10, 17))))
                .isInstanceOf(InvalidDailySaleException.class);
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void shouldUseColomboClockInTheApplication() {
        assertThat(new ClockConfig().clock().getZone()).isEqualTo(COLOMBO);
    }

    // The duplicate check passed, but another request recorded the same date before this insert.
    @Test
    void shouldReportDuplicateWhenUniqueConstraintRejectsConcurrentInsert() {
        DailySaleService service = serviceAt(COLOMBO_JUST_AFTER_MIDNIGHT);
        UUID companyId = UUID.randomUUID();
        when(repository.existsByCompanyIdAndSaleDate(companyId, LocalDate.of(2026, 10, 15))).thenReturn(false);
        when(repository.saveAndFlush(any(DailySale.class)))
                .thenThrow(new DataIntegrityViolationException("uq_daily_sales_company_sale_date"));

        assertThatThrownBy(() -> service.createDailySale(user(), companyId, request(LocalDate.of(2026, 10, 15))))
                .isInstanceOf(DuplicateSaleDateException.class);
    }

    private DailySaleService serviceAt(Instant instant) {
        return new DailySaleService(repository, companyAccessVerifier, Clock.fixed(instant, COLOMBO), transactionManager);
    }

    private static CreateDailySaleRequest request(LocalDate saleDate) {
        return new CreateDailySaleRequest(saleDate, new BigDecimal("50000.00"), BigDecimal.ZERO);
    }

    private static Jwt user() {
        return Jwt.withTokenValue("user-access-token")
                .header("alg", "HS256")
                .subject(UUID.randomUUID().toString())
                .build();
    }
}
