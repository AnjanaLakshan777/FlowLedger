package com.flowledger.bankintegration.service;

import com.flowledger.bankintegration.dto.BankBalanceSnapshotResponse;
import com.flowledger.bankintegration.dto.BankTransactionResponse;
import com.flowledger.bankintegration.dto.ManualBalanceRequest;
import com.flowledger.bankintegration.dto.ManualTransactionRequest;
import com.flowledger.bankintegration.entity.BankAccount;
import com.flowledger.bankintegration.entity.BankAccountType;
import com.flowledger.bankintegration.entity.BankIntegrationAuditEvent;
import com.flowledger.bankintegration.entity.BankTransaction;
import com.flowledger.bankintegration.entity.IntegrationMode;
import com.flowledger.bankintegration.entity.OutboxEvent;
import com.flowledger.bankintegration.entity.TransactionCategory;
import com.flowledger.bankintegration.exception.BankAccountNotActiveException;
import com.flowledger.bankintegration.exception.BankAccountNotFoundException;
import com.flowledger.bankintegration.exception.BankTransactionNotFoundException;
import com.flowledger.bankintegration.exception.ConnectionModeMismatchException;
import com.flowledger.bankintegration.exception.DuplicateBalanceException;
import com.flowledger.bankintegration.exception.InvalidRequestException;
import com.flowledger.bankintegration.repository.BankAccountRepository;
import com.flowledger.bankintegration.repository.BankBalanceSnapshotRepository;
import com.flowledger.bankintegration.repository.BankIntegrationAuditEventRepository;
import com.flowledger.bankintegration.repository.BankTransactionRepository;
import com.flowledger.bankintegration.repository.ManualEntryStore;
import com.flowledger.bankintegration.repository.OutboxEventRepository;
import com.flowledger.bankintegration.security.CompanyAccessVerifier;
import com.flowledger.bankintegration.security.CompanyRole;
import com.flowledger.bankintegration.statement.TransactionFingerprints;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Bank data typed in by hand for an account whose connection is MANUAL: confirmed balances and
 * individual transactions. It is the same data a statement import records, so the forecast and
 * Forecast vs Actual use it the same way. Accounts that import statements cannot take manual
 * entries, so nothing is recorded twice.
 */
@Slf4j
@Service
public class ManualEntryService {

    // Entering bank data is like importing a statement.
    private static final Set<CompanyRole> ENTRY_ROLES =
            EnumSet.of(CompanyRole.COMPANY_ADMIN, CompanyRole.FINANCE_CONTROLLER);

    private final BankAccountRepository bankAccountRepository;
    private final BankTransactionRepository transactionRepository;
    private final BankBalanceSnapshotRepository snapshotRepository;
    private final ManualEntryStore store;
    private final BankIntegrationAuditEventRepository auditEventRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final CompanyAccessVerifier companyAccessVerifier;
    private final Clock clock;
    private final TransactionTemplate writeTransaction;

    public ManualEntryService(BankAccountRepository bankAccountRepository,
                              BankTransactionRepository transactionRepository,
                              BankBalanceSnapshotRepository snapshotRepository,
                              ManualEntryStore store,
                              BankIntegrationAuditEventRepository auditEventRepository,
                              OutboxEventRepository outboxEventRepository,
                              CompanyAccessVerifier companyAccessVerifier,
                              Clock clock,
                              PlatformTransactionManager transactionManager) {
        this.bankAccountRepository = bankAccountRepository;
        this.transactionRepository = transactionRepository;
        this.snapshotRepository = snapshotRepository;
        this.store = store;
        this.auditEventRepository = auditEventRepository;
        this.outboxEventRepository = outboxEventRepository;
        this.companyAccessVerifier = companyAccessVerifier;
        this.clock = clock;
        this.writeTransaction = new TransactionTemplate(transactionManager);
    }

    public BankTransactionResponse addTransaction(Jwt user, UUID companyId, UUID bankAccountId,
                                                  ManualTransactionRequest request) {
        companyAccessVerifier.verify(user, companyId, ENTRY_ROLES);
        UUID actor = UUID.fromString(user.getSubject());
        if (request.transactionDate().isAfter(LocalDate.now(clock))) {
            throw new InvalidRequestException("transactionDate must not be in the future.");
        }
        TransactionCategory category = request.category() == null ? TransactionCategory.UNCLASSIFIED : request.category();
        if (!category.fits(request.direction())) {
            throw new InvalidRequestException(category + " cannot be used for a " + request.direction() + " transaction.");
        }

        String otherDescription = OtherDescriptions.require(category, request.otherDescription());

        return writeTransaction.execute(status -> {
            BankAccount account = manualAccount(companyId, bankAccountId);
            UUID id = store.insertTransaction(companyId, account.getId(), account.getCurrency(), request, category,
                    otherDescription,
                    actor, clock.instant(), TransactionFingerprints.sha256(
                            ("MANUAL:" + UUID.randomUUID()).getBytes(StandardCharsets.UTF_8)));
            BankTransaction transaction = transactionRepository.findByCompanyIdAndId(companyId, id).orElseThrow();

            Map<String, Object> details = transactionDetails(transaction);
            audit(companyId, actor, "BANK_TRANSACTION_ENTERED", "BANK_TRANSACTION", id, null, details);
            publish("BankTransaction", id, "BankTransactionEntered", details);
            log.info("Manual bank transaction {} entered", id);
            return BankTransactionResponse.from(transaction);
        });
    }

    public void deleteTransaction(Jwt user, UUID companyId, UUID bankAccountId, UUID transactionId) {
        companyAccessVerifier.verify(user, companyId, ENTRY_ROLES);
        UUID actor = UUID.fromString(user.getSubject());

        writeTransaction.executeWithoutResult(status -> {
            BankTransaction transaction = transactionRepository.lockByCompanyIdAndId(companyId, transactionId)
                    .filter(found -> found.getBankAccountId().equals(bankAccountId))
                    .orElseThrow(BankTransactionNotFoundException::new);
            if (!"MANUAL".equals(transaction.getEntrySource())) {
                throw new InvalidRequestException("Only manually entered transactions can be deleted.");
            }
            Map<String, Object> details = transactionDetails(transaction);
            store.deleteManualTransaction(companyId, transactionId);
            audit(companyId, actor, "BANK_TRANSACTION_DELETED", "BANK_TRANSACTION", transactionId, details, null);
            publish("BankTransaction", transactionId, "BankTransactionDeleted", details);
        });
        log.info("Manual bank transaction {} deleted", transactionId);
    }

    public BankBalanceSnapshotResponse recordBalance(Jwt user, UUID companyId, UUID bankAccountId,
                                                     ManualBalanceRequest request) {
        companyAccessVerifier.verify(user, companyId, ENTRY_ROLES);
        UUID actor = UUID.fromString(user.getSubject());
        Instant asOf = request.balanceAsOf().toInstant();
        if (asOf.isAfter(clock.instant())) {
            throw new InvalidRequestException("balanceAsOf must not be in the future.");
        }

        return writeTransaction.execute(status -> {
            BankAccount account = manualAccount(companyId, bankAccountId);
            UUID id = store.insertBalanceSnapshot(companyId, account.getId(), account.getCurrency(),
                            request.balanceAmount(), request.balanceAsOf())
                    .orElseThrow(DuplicateBalanceException::new);

            Map<String, Object> details = new LinkedHashMap<>();
            details.put("companyId", companyId.toString());
            details.put("bankAccountId", account.getId().toString());
            details.put("balanceAmount", request.balanceAmount().toPlainString());
            details.put("currency", account.getCurrency());
            details.put("balanceAsOf", request.balanceAsOf().toString());
            audit(companyId, actor, "BANK_BALANCE_ENTERED", "BANK_BALANCE_SNAPSHOT", id, null, details);
            publish("BankAccount", account.getId(), "BankBalanceSnapshotRecorded", details);
            return BankBalanceSnapshotResponse.from(snapshotRepository.findById(id).orElseThrow());
        });
    }

    private BankAccount manualAccount(UUID companyId, UUID bankAccountId) {
        BankAccount account = bankAccountRepository.findByCompanyIdAndId(companyId, bankAccountId)
                .orElseThrow(BankAccountNotFoundException::new);
        if (!account.isActive() || account.getAccountType() != BankAccountType.CURRENT) {
            throw new BankAccountNotActiveException("Bank data can only be entered for an active current account.");
        }
        if (account.getIntegrationMode() != IntegrationMode.MANUAL) {
            throw new ConnectionModeMismatchException(
                    "This account's bank data comes from statement imports. Change its connection to manual entry first.");
        }
        return account;
    }

    private static Map<String, Object> transactionDetails(BankTransaction transaction) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("bankTransactionId", transaction.getId().toString());
        details.put("companyId", transaction.getCompanyId().toString());
        details.put("bankAccountId", transaction.getBankAccountId().toString());
        details.put("transactionDate", transaction.getTransactionDate().toString());
        details.put("direction", transaction.getDirection().name());
        details.put("amount", transaction.getAmount().toPlainString());
        details.put("currency", transaction.getCurrency());
        details.put("category", transaction.getCategory().name());
        details.put("otherDescription", transaction.getOtherDescription());
        details.put("chequeNumber", transaction.getChequeNumber());
        return details;
    }

    private void audit(UUID companyId, UUID actor, String action, String entityType, UUID entityId,
                       Map<String, Object> before, Map<String, Object> after) {
        auditEventRepository.save(new BankIntegrationAuditEvent(companyId, actor, action, entityType, entityId,
                null, before, after));
    }

    private void publish(String aggregateType, UUID aggregateId, String eventType, Map<String, Object> payload) {
        outboxEventRepository.save(new OutboxEvent(aggregateType, aggregateId, eventType, payload));
    }
}
