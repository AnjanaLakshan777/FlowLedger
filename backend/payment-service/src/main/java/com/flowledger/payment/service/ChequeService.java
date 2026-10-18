package com.flowledger.payment.service;

import com.flowledger.payment.client.CurrentAccountClient;
import com.flowledger.payment.dto.CancelChequeRequest;
import com.flowledger.payment.dto.ChequeRequest;
import com.flowledger.payment.dto.ChequeResponse;
import com.flowledger.payment.entity.Cheque;
import com.flowledger.payment.entity.ChequeCategory;
import com.flowledger.payment.entity.ChequeDetails;
import com.flowledger.payment.entity.ChequeStatus;
import com.flowledger.payment.entity.OutboxEvent;
import com.flowledger.payment.entity.PaymentAuditAction;
import com.flowledger.payment.entity.PaymentAuditEvent;
import com.flowledger.payment.exception.ChequeNotChangeableException;
import com.flowledger.payment.exception.ChequeNotFoundException;
import com.flowledger.payment.exception.ChequeVersionConflictException;
import com.flowledger.payment.exception.DuplicateChequeNumberException;
import com.flowledger.payment.exception.InvalidChequeException;
import com.flowledger.payment.repository.ChequeRepository;
import com.flowledger.payment.repository.OutboxEventRepository;
import com.flowledger.payment.repository.PaymentAuditEventRepository;
import com.flowledger.payment.security.CompanyAccessVerifier;
import com.flowledger.payment.security.CompanyRole;
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
 * Cheque payments. Calls to identity-service and bank-integration-service finish before any
 * database transaction starts, so slow services do not hold database connections. Each change
 * writes the cheque, its audit event and its outbox event in one transaction.
 */
@Service
public class ChequeService {

    // From the UI permission matrix: the finance pages include cheques, but only roles with
    // "cheques.manage" may add, edit or cancel them. CASHIER has no access. A SYSTEM_ADMIN needs
    // one of these company roles; platform access alone grants nothing for financial data.
    private static final Set<CompanyRole> VIEW_ROLES =
            EnumSet.of(CompanyRole.COMPANY_ADMIN, CompanyRole.FINANCE_CONTROLLER, CompanyRole.ACCOUNTANT);
    private static final Set<CompanyRole> MANAGE_ROLES =
            EnumSet.of(CompanyRole.COMPANY_ADMIN, CompanyRole.FINANCE_CONTROLLER);

    private static final String AGGREGATE_TYPE = "Cheque";

    private final ChequeRepository chequeRepository;
    private final PaymentAuditEventRepository auditEventRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final CompanyAccessVerifier companyAccessVerifier;
    private final CurrentAccountClient currentAccountClient;
    private final Clock clock;
    private final TransactionTemplate writeTransaction;
    private final TransactionTemplate readTransaction;

    public ChequeService(ChequeRepository chequeRepository,
                         PaymentAuditEventRepository auditEventRepository,
                         OutboxEventRepository outboxEventRepository,
                         CompanyAccessVerifier companyAccessVerifier,
                         CurrentAccountClient currentAccountClient,
                         Clock clock,
                         PlatformTransactionManager transactionManager) {
        this.chequeRepository = chequeRepository;
        this.auditEventRepository = auditEventRepository;
        this.outboxEventRepository = outboxEventRepository;
        this.companyAccessVerifier = companyAccessVerifier;
        this.currentAccountClient = currentAccountClient;
        this.clock = clock;
        this.writeTransaction = new TransactionTemplate(transactionManager);
        this.readTransaction = new TransactionTemplate(transactionManager);
        this.readTransaction.setReadOnly(true);
    }

    public ChequeResponse addCheque(Jwt user, UUID companyId, ChequeRequest request) {
        companyAccessVerifier.verify(user, companyId, MANAGE_ROLES);
        ChequeDetails details = validatedDetails(request);
        UUID bankAccountId = currentAccountClient.findActiveCurrentAccount(user, companyId);
        UUID actor = userId(user);

        return writeTransaction.execute(status -> {
            if (chequeRepository.existsByCompanyIdAndBankAccountIdAndChequeNumber(
                    companyId, bankAccountId, details.chequeNumber())) {
                throw new DuplicateChequeNumberException();
            }
            Cheque cheque = saveCheque(new Cheque(companyId, bankAccountId, details, actor));
            record(cheque, PaymentAuditAction.CHEQUE_ISSUED, "ChequeIssued", actor, null, null);
            return ChequeResponse.from(cheque);
        });
    }

    public ChequeResponse updateCheque(Jwt user, UUID companyId, UUID chequeId, ChequeRequest request) {
        companyAccessVerifier.verify(user, companyId, MANAGE_ROLES);
        if (request.version() == null) {
            throw new InvalidChequeException("version", "version is required");
        }
        ChequeDetails details = validatedDetails(request);
        UUID actor = userId(user);

        return writeTransaction.execute(status -> {
            Cheque cheque = changeableCheque(companyId, chequeId, request.version());
            if (!cheque.getChequeNumber().equals(details.chequeNumber())
                    && chequeRepository.existsByCompanyIdAndBankAccountIdAndChequeNumber(
                    companyId, cheque.getBankAccountId(), details.chequeNumber())) {
                throw new DuplicateChequeNumberException();
            }

            Map<String, Object> before = cheque.snapshot();
            if (cheque.update(details, actor, clock.instant())) {
                cheque = saveCheque(cheque);
                record(cheque, PaymentAuditAction.CHEQUE_UPDATED, "ChequeUpdated", actor, null, before);
            }
            return ChequeResponse.from(cheque);
        });
    }

    // A cancelled cheque stays on record but is excluded from forecast outflows.
    public ChequeResponse cancelCheque(Jwt user, UUID companyId, UUID chequeId, CancelChequeRequest request) {
        companyAccessVerifier.verify(user, companyId, MANAGE_ROLES);
        UUID actor = userId(user);

        return writeTransaction.execute(status -> {
            Cheque cheque = changeableCheque(companyId, chequeId, request.version());
            Map<String, Object> before = cheque.snapshot();
            cheque.cancel(actor, clock.instant());
            cheque = saveCheque(cheque);
            record(cheque, PaymentAuditAction.CHEQUE_CANCELLED, "ChequeCancelled", actor,
                    request.reason().trim(), before);
            return ChequeResponse.from(cheque);
        });
    }

    public ChequeResponse getCheque(Jwt user, UUID companyId, UUID chequeId) {
        companyAccessVerifier.verify(user, companyId, VIEW_ROLES);
        return readTransaction.execute(status -> chequeRepository.findByCompanyIdAndId(companyId, chequeId)
                .map(ChequeResponse::from)
                .orElseThrow(ChequeNotFoundException::new));
    }

    public List<ChequeResponse> listCheques(Jwt user, UUID companyId, ChequeStatus chequeStatus,
                                            LocalDate clearingFrom, LocalDate clearingTo) {
        companyAccessVerifier.verify(user, companyId, VIEW_ROLES);
        if (clearingFrom != null && clearingTo != null && clearingTo.isBefore(clearingFrom)) {
            throw new InvalidChequeException("clearingTo", "clearingTo must not be before clearingFrom");
        }
        return readTransaction.execute(status -> chequeRepository
                .findForCompany(companyId, chequeStatus, clearingFrom, clearingTo).stream()
                .map(ChequeResponse::from)
                .toList());
    }

    // The same rules as the UI: the expected clearing date may not be before the issue date, and
    // must be after today, since earlier payments are already part of the actual bank balance.
    private ChequeDetails validatedDetails(ChequeRequest request) {
        if (request.expectedClearingDate().isBefore(request.issueDate())) {
            throw new InvalidChequeException("expectedClearingDate",
                    "expectedClearingDate must not be before issueDate");
        }
        if (!request.expectedClearingDate().isAfter(LocalDate.now(clock))) {
            throw new InvalidChequeException("expectedClearingDate", "expectedClearingDate must be after today");
        }
        return new ChequeDetails(
                request.chequeNumber(),
                request.payeeName().trim(),
                request.category(),
                otherDescription(request),
                request.amount(),
                request.issueDate(),
                request.expectedClearingDate(),
                request.notes() == null || request.notes().isBlank() ? null : request.notes().trim()
        );
    }

    // An OTHER_EXPENSES cheque must say what the payment was, so it reads as "Other Expenses - Audit fee".
    // The description is kept only for that category.
    private static String otherDescription(ChequeRequest request) {
        if (request.category() != ChequeCategory.OTHER_EXPENSES) {
            return null;
        }
        if (request.otherDescription() == null || request.otherDescription().isBlank()) {
            throw new InvalidChequeException("otherDescription",
                    "otherDescription is required when the category is OTHER_EXPENSES");
        }
        return request.otherDescription().strip();
    }

    private Cheque changeableCheque(UUID companyId, UUID chequeId, int expectedVersion) {
        Cheque cheque = chequeRepository.findByCompanyIdAndId(companyId, chequeId)
                .orElseThrow(ChequeNotFoundException::new);
        if (cheque.getVersion() != expectedVersion) {
            throw new ChequeVersionConflictException();
        }
        if (!cheque.isChangeable(LocalDate.now(clock))) {
            throw new ChequeNotChangeableException();
        }
        return cheque;
    }

    private Cheque saveCheque(Cheque cheque) {
        try {
            return chequeRepository.saveAndFlush(cheque);
        } catch (ObjectOptimisticLockingFailureException e) {
            // Another request changed the cheque after it was read in this transaction.
            throw new ChequeVersionConflictException();
        } catch (DataIntegrityViolationException e) {
            // Request validation covers the other constraints, so this is a concurrent request
            // recording the same cheque number after the duplicate check.
            throw new DuplicateChequeNumberException();
        }
    }

    private void record(Cheque cheque, PaymentAuditAction action, String eventType, UUID actor, String reason,
                        Map<String, Object> before) {
        auditEventRepository.save(new PaymentAuditEvent(cheque, action, actor, reason, before));

        Map<String, Object> payload = cheque.snapshot();
        payload.remove("payeeName");
        payload.remove("notes");
        payload.remove("otherDescription");
        payload.put("chequeId", cheque.getId().toString());
        payload.put("companyId", cheque.getCompanyId().toString());
        outboxEventRepository.save(new OutboxEvent(AGGREGATE_TYPE, cheque.getId(), eventType, payload));
    }

    private static UUID userId(Jwt user) {
        return UUID.fromString(user.getSubject());
    }
}
