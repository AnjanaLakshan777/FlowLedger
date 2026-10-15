package com.flowledger.bankintegration.service;

import com.flowledger.bankintegration.dto.BankBalanceSnapshotResponse;
import com.flowledger.bankintegration.exception.BalanceNotAvailableException;
import com.flowledger.bankintegration.exception.BankAccountNotFoundException;
import com.flowledger.bankintegration.repository.BankAccountRepository;
import com.flowledger.bankintegration.repository.BankBalanceSnapshotRepository;
import com.flowledger.bankintegration.security.CompanyAccessVerifier;
import com.flowledger.bankintegration.security.CompanyRole;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

/**
 * Confirmed account balances. A balance is business data, so only company roles may read it;
 * there is no platform-admin bypass. A missing balance is reported as missing, never as zero.
 */
@Service
public class BankBalanceService {

    private static final Set<CompanyRole> VIEW_ROLES =
            EnumSet.of(CompanyRole.COMPANY_ADMIN, CompanyRole.FINANCE_CONTROLLER, CompanyRole.ACCOUNTANT);

    private final BankAccountRepository bankAccountRepository;
    private final BankBalanceSnapshotRepository snapshotRepository;
    private final CompanyAccessVerifier companyAccessVerifier;
    private final TransactionTemplate readTransaction;

    public BankBalanceService(BankAccountRepository bankAccountRepository,
                              BankBalanceSnapshotRepository snapshotRepository,
                              CompanyAccessVerifier companyAccessVerifier,
                              PlatformTransactionManager transactionManager) {
        this.bankAccountRepository = bankAccountRepository;
        this.snapshotRepository = snapshotRepository;
        this.companyAccessVerifier = companyAccessVerifier;
        this.readTransaction = new TransactionTemplate(transactionManager);
        this.readTransaction.setReadOnly(true);
    }

    public BankBalanceSnapshotResponse getLatestBalance(Jwt user, UUID companyId, UUID bankAccountId) {
        companyAccessVerifier.verify(user, companyId, VIEW_ROLES);
        return readTransaction.execute(status -> {
            bankAccountRepository.findByCompanyIdAndId(companyId, bankAccountId)
                    .orElseThrow(BankAccountNotFoundException::new);
            return snapshotRepository
                    .findFirstByCompanyIdAndBankAccountIdOrderByBalanceAsOfDescCreatedAtDesc(companyId, bankAccountId)
                    .map(BankBalanceSnapshotResponse::from)
                    .orElseThrow(BalanceNotAvailableException::new);
        });
    }
}
