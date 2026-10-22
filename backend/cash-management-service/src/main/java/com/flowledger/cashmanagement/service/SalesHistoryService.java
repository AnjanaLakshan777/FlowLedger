package com.flowledger.cashmanagement.service;

import com.flowledger.cashmanagement.dto.SalesHistoryResponse;
import com.flowledger.cashmanagement.exception.InvalidDailySaleException;
import com.flowledger.cashmanagement.salesimport.SalesImportStore;
import com.flowledger.cashmanagement.security.CompanyAccessVerifier;
import com.flowledger.cashmanagement.security.CompanyRole;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

/**
 * Recorded sales (approved imports and manual daily sales) for the forecast's previous-year pattern
 * and growth ratio. Read with the user's own
 * token by forecast-service, so the roles are those that may create forecasts (the Cashier has
 * neither). Company access is checked before any database work.
 */
@Service
public class SalesHistoryService {

    static final Set<CompanyRole> HISTORY_ROLES =
            EnumSet.of(CompanyRole.COMPANY_ADMIN, CompanyRole.FINANCE_CONTROLLER, CompanyRole.ACCOUNTANT);
    // A forecast needs about 64 weeks of history; this bounds one response.
    static final int MAX_DAYS = 800;

    private final SalesImportStore store;
    private final CompanyAccessVerifier companyAccessVerifier;
    private final TransactionTemplate readTransaction;

    public SalesHistoryService(SalesImportStore store, CompanyAccessVerifier companyAccessVerifier,
                               PlatformTransactionManager transactionManager) {
        this.store = store;
        this.companyAccessVerifier = companyAccessVerifier;
        this.readTransaction = new TransactionTemplate(transactionManager);
        this.readTransaction.setReadOnly(true);
    }

    public SalesHistoryResponse approvedHistory(Jwt user, UUID companyId, LocalDate from, LocalDate to) {
        companyAccessVerifier.verify(user, companyId, HISTORY_ROLES);
        return history(companyId, from, to);
    }

    /**
     * The same history without a user, for forecast-service's daily scheduled forecast through the
     * internal API; only callers with the internal API key get here.
     */
    public SalesHistoryResponse history(UUID companyId, LocalDate from, LocalDate to) {
        if (from == null || to == null) {
            throw new InvalidDailySaleException(from == null ? "from" : "to", "from and to are required");
        }
        if (to.isBefore(from)) {
            throw new InvalidDailySaleException("to", "to must not be before from");
        }
        if (ChronoUnit.DAYS.between(from, to) >= MAX_DAYS) {
            throw new InvalidDailySaleException("to", "at most " + MAX_DAYS + " days can be requested at once");
        }
        return new SalesHistoryResponse(from, to,
                readTransaction.execute(status -> store.salesHistory(companyId, from, to)));
    }
}
