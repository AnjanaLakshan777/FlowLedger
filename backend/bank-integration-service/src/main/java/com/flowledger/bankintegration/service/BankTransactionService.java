package com.flowledger.bankintegration.service;

import com.flowledger.bankintegration.dto.BankTransactionResponse;
import com.flowledger.bankintegration.dto.ClassifyTransactionRequest;
import com.flowledger.bankintegration.entity.BankIntegrationAuditEvent;
import com.flowledger.bankintegration.entity.BankTransaction;
import com.flowledger.bankintegration.entity.OutboxEvent;
import com.flowledger.bankintegration.entity.TransactionCategory;
import com.flowledger.bankintegration.exception.BankTransactionNotFoundException;
import com.flowledger.bankintegration.exception.InvalidRequestException;
import com.flowledger.bankintegration.repository.BankIntegrationAuditEventRepository;
import com.flowledger.bankintegration.repository.BankTransactionRepository;
import com.flowledger.bankintegration.repository.OutboxEventRepository;
import com.flowledger.bankintegration.security.CompanyAccessVerifier;
import com.flowledger.bankintegration.security.CompanyRole;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Imported bank transactions and their manual classification. Classification decides what counts
 * in Forecast vs Actual, so every change is audited with the actor, and no category is ever
 * inferred from a description.
 */
@Service
public class BankTransactionService {

    private static final Set<CompanyRole> VIEW_ROLES =
            EnumSet.of(CompanyRole.COMPANY_ADMIN, CompanyRole.FINANCE_CONTROLLER, CompanyRole.ACCOUNTANT);
    private static final Set<CompanyRole> CLASSIFY_ROLES =
            EnumSet.of(CompanyRole.COMPANY_ADMIN, CompanyRole.FINANCE_CONTROLLER);

    private final BankTransactionRepository transactionRepository;
    private final BankIntegrationAuditEventRepository auditEventRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final CompanyAccessVerifier companyAccessVerifier;
    private final Clock clock;
    private final TransactionTemplate writeTransaction;
    private final TransactionTemplate readTransaction;

    public BankTransactionService(BankTransactionRepository transactionRepository,
                                  BankIntegrationAuditEventRepository auditEventRepository,
                                  OutboxEventRepository outboxEventRepository,
                                  CompanyAccessVerifier companyAccessVerifier,
                                  Clock clock,
                                  PlatformTransactionManager transactionManager) {
        this.transactionRepository = transactionRepository;
        this.auditEventRepository = auditEventRepository;
        this.outboxEventRepository = outboxEventRepository;
        this.companyAccessVerifier = companyAccessVerifier;
        this.clock = clock;
        this.writeTransaction = new TransactionTemplate(transactionManager);
        this.readTransaction = new TransactionTemplate(transactionManager);
        this.readTransaction.setReadOnly(true);
    }

    public List<BankTransactionResponse> listTransactions(Jwt user, UUID companyId, UUID bankAccountId,
                                                          TransactionCategory category, LocalDate from, LocalDate to) {
        companyAccessVerifier.verify(user, companyId, VIEW_ROLES);
        if (from != null && to != null && to.isBefore(from)) {
            throw new InvalidRequestException("to must not be before from.");
        }
        return readTransaction.execute(status -> transactionRepository
                .findForCompany(companyId, bankAccountId, category, from, to).stream()
                .map(BankTransactionResponse::from)
                .toList());
    }

    public BankTransactionResponse getTransaction(Jwt user, UUID companyId, UUID transactionId) {
        companyAccessVerifier.verify(user, companyId, VIEW_ROLES);
        return readTransaction.execute(status -> transactionRepository.findByCompanyIdAndId(companyId, transactionId)
                .map(BankTransactionResponse::from)
                .orElseThrow(BankTransactionNotFoundException::new));
    }

    public BankTransactionResponse classify(Jwt user, UUID companyId, UUID transactionId,
                                            ClassifyTransactionRequest request) {
        companyAccessVerifier.verify(user, companyId, CLASSIFY_ROLES);
        UUID actor = UUID.fromString(user.getSubject());
        String reason = request.reason() == null || request.reason().isBlank() ? null : request.reason().strip();

        return writeTransaction.execute(status -> {
            BankTransaction transaction = transactionRepository.lockByCompanyIdAndId(companyId, transactionId)
                    .orElseThrow(BankTransactionNotFoundException::new);
            if (!request.category().fits(transaction.getDirection())) {
                throw new InvalidRequestException(request.category() + " cannot be used for a "
                        + transaction.getDirection() + " transaction.");
            }

            Map<String, Object> before = transaction.classificationSnapshot();
            if (transaction.classify(request.category(), actor, clock.instant())) {
                transaction = transactionRepository.saveAndFlush(transaction);
                auditEventRepository.save(new BankIntegrationAuditEvent(companyId, actor, "TRANSACTION_CLASSIFIED",
                        "BANK_TRANSACTION", transaction.getId(), reason, before, transaction.classificationSnapshot()));
                outboxEventRepository.save(new OutboxEvent("BankTransaction", transaction.getId(),
                        "BankTransactionCategoryChanged", categoryPayload(transaction)));
            }
            return BankTransactionResponse.from(transaction);
        });
    }

    private static Map<String, Object> categoryPayload(BankTransaction transaction) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("bankTransactionId", transaction.getId().toString());
        payload.put("companyId", transaction.getCompanyId().toString());
        payload.put("bankAccountId", transaction.getBankAccountId().toString());
        payload.put("transactionDate", transaction.getTransactionDate().toString());
        payload.put("direction", transaction.getDirection().name());
        payload.put("amount", transaction.getAmount().toPlainString());
        payload.put("category", transaction.getCategory().name());
        return payload;
    }
}
