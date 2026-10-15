package com.flowledger.bankintegration.service;

import com.flowledger.bankintegration.dto.BankAccountResponse;
import com.flowledger.bankintegration.dto.CreateBankAccountRequest;
import com.flowledger.bankintegration.entity.BankAccount;
import com.flowledger.bankintegration.entity.BankAccountStatus;
import com.flowledger.bankintegration.entity.BankAccountType;
import com.flowledger.bankintegration.exception.ActiveCurrentAccountExistsException;
import com.flowledger.bankintegration.exception.BankAccountNotFoundException;
import com.flowledger.bankintegration.repository.BankAccountRepository;
import com.flowledger.bankintegration.security.CompanyAccessVerifier;
import com.flowledger.bankintegration.security.CompanyRole;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class BankAccountService {

    // Follows the UI permission matrix: the finance roles may view bank accounts, but configuring
    // them is company administration (COMPANY_ADMIN, or a SYSTEM_ADMIN in any company). CASHIER
    // has no access. A SYSTEM_ADMIN may also view them, since configuring requires seeing them.
    private static final Set<CompanyRole> VIEW_ROLES =
            EnumSet.of(CompanyRole.COMPANY_ADMIN, CompanyRole.FINANCE_CONTROLLER, CompanyRole.ACCOUNTANT);
    private static final Set<CompanyRole> CONFIGURE_ROLES = EnumSet.of(CompanyRole.COMPANY_ADMIN);

    private final BankAccountRepository bankAccountRepository;
    private final CompanyAccessVerifier companyAccessVerifier;

    @Transactional
    public BankAccountResponse createBankAccount(Jwt user, UUID companyId, CreateBankAccountRequest request) {
        companyAccessVerifier.verifyOrPlatformAdmin(user, companyId, CONFIGURE_ROLES);

        if (request.accountType() == BankAccountType.CURRENT && bankAccountRepository
                .existsByCompanyIdAndAccountTypeAndStatus(companyId, BankAccountType.CURRENT, BankAccountStatus.ACTIVE)) {
            throw new ActiveCurrentAccountExistsException();
        }

        BankAccount account = new BankAccount(
                companyId,
                request.bankName().trim(),
                request.accountName().trim(),
                request.accountType(),
                request.accountNumberLast4(),
                request.integrationMode(),
                BankAccountStatus.ACTIVE
        );
        try {
            return BankAccountResponse.from(bankAccountRepository.saveAndFlush(account));
        } catch (DataIntegrityViolationException e) {
            // Request validation covers the other constraints, so this is a concurrent request
            // creating the company's active current account after the check above.
            throw new ActiveCurrentAccountExistsException();
        }
    }

    @Transactional(readOnly = true)
    public BankAccountResponse getBankAccount(Jwt user, UUID companyId, UUID accountId) {
        companyAccessVerifier.verifyOrPlatformAdmin(user, companyId, VIEW_ROLES);
        return bankAccountRepository.findByCompanyIdAndId(companyId, accountId)
                .map(BankAccountResponse::from)
                .orElseThrow(BankAccountNotFoundException::new);
    }

    @Transactional(readOnly = true)
    public List<BankAccountResponse> listBankAccounts(Jwt user, UUID companyId) {
        companyAccessVerifier.verifyOrPlatformAdmin(user, companyId, VIEW_ROLES);
        return bankAccountRepository.findByCompanyIdOrderByCreatedAtAsc(companyId).stream()
                .map(BankAccountResponse::from)
                .toList();
    }
}
