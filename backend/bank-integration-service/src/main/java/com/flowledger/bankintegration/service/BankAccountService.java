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
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class BankAccountService {

    private final BankAccountRepository bankAccountRepository;
    private final CompanyAccessVerifier companyAccessVerifier;

    @Transactional
    public BankAccountResponse createBankAccount(UUID userId, UUID companyId, CreateBankAccountRequest request) {
        companyAccessVerifier.verify(userId, companyId);

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
    public BankAccountResponse getBankAccount(UUID userId, UUID companyId, UUID accountId) {
        companyAccessVerifier.verify(userId, companyId);
        return bankAccountRepository.findByCompanyIdAndId(companyId, accountId)
                .map(BankAccountResponse::from)
                .orElseThrow(BankAccountNotFoundException::new);
    }

    @Transactional(readOnly = true)
    public List<BankAccountResponse> listBankAccounts(UUID userId, UUID companyId) {
        companyAccessVerifier.verify(userId, companyId);
        return bankAccountRepository.findByCompanyIdOrderByCreatedAtAsc(companyId).stream()
                .map(BankAccountResponse::from)
                .toList();
    }
}
