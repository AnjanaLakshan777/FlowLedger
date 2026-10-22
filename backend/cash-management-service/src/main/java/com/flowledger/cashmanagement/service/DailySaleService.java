package com.flowledger.cashmanagement.service;

import com.flowledger.cashmanagement.dto.CreateDailySaleRequest;
import com.flowledger.cashmanagement.dto.DailySaleResponse;
import com.flowledger.cashmanagement.dto.UpdateDailySaleRequest;
import com.flowledger.cashmanagement.entity.DailySale;
import com.flowledger.cashmanagement.entity.EntrySource;
import com.flowledger.cashmanagement.exception.DailySaleNotChangeableException;
import com.flowledger.cashmanagement.exception.DailySaleNotFoundException;
import com.flowledger.cashmanagement.exception.DuplicateSaleDateException;
import com.flowledger.cashmanagement.exception.InvalidDailySaleException;
import com.flowledger.cashmanagement.repository.AuditEventStore;
import com.flowledger.cashmanagement.repository.DailySaleRepository;
import com.flowledger.cashmanagement.security.CompanyAccessVerifier;
import com.flowledger.cashmanagement.security.CompanyRole;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Each operation checks company access with identity-service first and only then opens a
 * database transaction. Transactions are started through TransactionTemplate rather than
 * {@code @Transactional}, which would take a pooled connection before the HTTP call and hold
 * it for as long as identity-service takes to answer.
 */
@Service
public class DailySaleService {

    // Confirmed: every company role may view all of its company's daily sales and record them.
    private static final Set<CompanyRole> VIEW_ROLES =
            EnumSet.of(CompanyRole.COMPANY_ADMIN, CompanyRole.FINANCE_CONTROLLER, CompanyRole.ACCOUNTANT,
                    CompanyRole.CASHIER);
    private static final Set<CompanyRole> CREATE_ROLES =
            EnumSet.of(CompanyRole.COMPANY_ADMIN, CompanyRole.FINANCE_CONTROLLER, CompanyRole.ACCOUNTANT,
                    CompanyRole.CASHIER);
    // Confirmed (2026-10-22): the Cashier records sales; corrections are for the Accountant and the roles above.
    private static final Set<CompanyRole> EDIT_ROLES =
            EnumSet.of(CompanyRole.COMPANY_ADMIN, CompanyRole.FINANCE_CONTROLLER, CompanyRole.ACCOUNTANT);

    private final DailySaleRepository dailySaleRepository;
    private final AuditEventStore auditEventStore;
    private final CompanyAccessVerifier companyAccessVerifier;
    private final Clock clock;
    private final TransactionTemplate writeTransaction;
    private final TransactionTemplate readTransaction;

    public DailySaleService(DailySaleRepository dailySaleRepository,
                            AuditEventStore auditEventStore,
                            CompanyAccessVerifier companyAccessVerifier,
                            Clock clock,
                            PlatformTransactionManager transactionManager) {
        this.dailySaleRepository = dailySaleRepository;
        this.auditEventStore = auditEventStore;
        this.companyAccessVerifier = companyAccessVerifier;
        this.clock = clock;
        this.writeTransaction = new TransactionTemplate(transactionManager);
        this.readTransaction = new TransactionTemplate(transactionManager);
        this.readTransaction.setReadOnly(true);
    }

    // Records the sales only. The matching bank inflow is the bank transaction imported in
    // bank-integration-service, so nothing here creates or implies money in the bank.
    public DailySaleResponse createDailySale(Jwt user, UUID companyId, CreateDailySaleRequest request) {
        companyAccessVerifier.verify(user, companyId, CREATE_ROLES);

        // Past dates are allowed: sales are usually recorded after the money has been deposited.
        if (request.saleDate().isAfter(LocalDate.now(clock))) {
            throw new InvalidDailySaleException("saleDate", "saleDate must not be in the future");
        }
        if (request.cashSalesAmount().signum() == 0 && request.cardSalesAmount().signum() == 0) {
            throw new InvalidDailySaleException("totalSalesAmount",
                    "cashSalesAmount and cardSalesAmount must not both be zero");
        }

        DailySale sale = new DailySale(
                companyId,
                request.saleDate(),
                request.cashSalesAmount(),
                request.cardSalesAmount(),
                EntrySource.UI,
                UUID.fromString(user.getSubject())
        );
        return writeTransaction.execute(status -> {
            if (dailySaleRepository.existsByCompanyIdAndSaleDate(companyId, request.saleDate())) {
                throw new DuplicateSaleDateException();
            }
            try {
                return DailySaleResponse.from(dailySaleRepository.saveAndFlush(sale));
            } catch (DataIntegrityViolationException e) {
                // Request validation covers the other constraints, so this is a concurrent request
                // recording the same company and sales date after the check above.
                throw new DuplicateSaleDateException();
            }
        });
    }

    /**
     * Corrects the cash and card amounts of a day entered by hand. A day from an approved import
     * is corrected by reversing that import instead. Every correction is audited with its reason
     * and the amounts before and after.
     */
    public DailySaleResponse updateDailySale(Jwt user, UUID companyId, UUID saleId, UpdateDailySaleRequest request) {
        companyAccessVerifier.verify(user, companyId, EDIT_ROLES);
        if (request.cashSalesAmount().signum() == 0 && request.cardSalesAmount().signum() == 0) {
            throw new InvalidDailySaleException("totalSalesAmount",
                    "cashSalesAmount and cardSalesAmount must not both be zero");
        }
        UUID actor = UUID.fromString(user.getSubject());

        return writeTransaction.execute(status -> {
            DailySale sale = dailySaleRepository.findByCompanyIdAndId(companyId, saleId)
                    .orElseThrow(DailySaleNotFoundException::new);
            if (sale.getEntrySource() != EntrySource.UI) {
                throw DailySaleNotChangeableException.imported();
            }
            if (!sale.getVersion().equals(request.version())) {
                throw DailySaleNotChangeableException.stale();
            }
            Map<String, Object> before = sale.snapshot();
            if (!sale.correct(request.cashSalesAmount(), request.cardSalesAmount(), actor, clock.instant())) {
                return DailySaleResponse.from(sale);
            }
            try {
                sale = dailySaleRepository.saveAndFlush(sale);
            } catch (ObjectOptimisticLockingFailureException e) {
                throw DailySaleNotChangeableException.stale();
            }
            auditEventStore.record(companyId, actor, "DAILY_SALE_CORRECTED", "DAILY_SALE", sale.getId(),
                    request.reason().strip(), before, sale.snapshot());
            return DailySaleResponse.from(sale);
        });
    }

    public DailySaleResponse getDailySale(Jwt user, UUID companyId, UUID saleId) {
        companyAccessVerifier.verify(user, companyId, VIEW_ROLES);
        return readTransaction.execute(status -> dailySaleRepository.findByCompanyIdAndId(companyId, saleId)
                .map(DailySaleResponse::from)
                .orElseThrow(DailySaleNotFoundException::new));
    }

    public List<DailySaleResponse> listDailySales(Jwt user, UUID companyId, LocalDate from, LocalDate to) {
        companyAccessVerifier.verify(user, companyId, VIEW_ROLES);

        if (from != null && to != null && to.isBefore(from)) {
            throw new InvalidDailySaleException("to", "to must not be before from");
        }
        return readTransaction.execute(status -> dailySaleRepository.findForCompany(companyId, from, to).stream()
                .map(DailySaleResponse::from)
                .toList());
    }
}
