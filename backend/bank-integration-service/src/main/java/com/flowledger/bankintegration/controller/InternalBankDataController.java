package com.flowledger.bankintegration.controller;

import com.flowledger.bankintegration.dto.BankAccountResponse;
import com.flowledger.bankintegration.dto.BankBalanceSnapshotResponse;
import com.flowledger.bankintegration.dto.BankTransactionResponse;
import com.flowledger.bankintegration.dto.StatementImportResponse;
import com.flowledger.bankintegration.exception.BalanceNotAvailableException;
import com.flowledger.bankintegration.exception.BankAccountNotFoundException;
import com.flowledger.bankintegration.exception.InvalidRequestException;
import com.flowledger.bankintegration.repository.BankAccountRepository;
import com.flowledger.bankintegration.repository.BankBalanceSnapshotRepository;
import com.flowledger.bankintegration.repository.BankStatementImportBatchRepository;
import com.flowledger.bankintegration.repository.BankTransactionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Read-only bank data for forecast-service's daily scheduled forecast, which runs without a
 * signed-in user. The same answers as the public reads it uses, for any company, so only callers
 * with the internal API key get here (SecurityConfig). Nothing is changed or audited.
 */
@RestController
@RequestMapping("/internal/v1/bank-integration/companies/{companyId}")
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class InternalBankDataController {

    private final BankAccountRepository bankAccountRepository;
    private final BankBalanceSnapshotRepository snapshotRepository;
    private final BankStatementImportBatchRepository batchRepository;
    private final BankTransactionRepository transactionRepository;

    @GetMapping("/bank-accounts")
    public List<BankAccountResponse> bankAccounts(@PathVariable UUID companyId) {
        return bankAccountRepository.findByCompanyIdOrderByCreatedAtAsc(companyId).stream()
                .map(BankAccountResponse::from)
                .toList();
    }

    @GetMapping("/bank-accounts/{bankAccountId}/balance-snapshots/latest")
    public BankBalanceSnapshotResponse latestBalance(@PathVariable UUID companyId, @PathVariable UUID bankAccountId) {
        bankAccountRepository.findByCompanyIdAndId(companyId, bankAccountId).orElseThrow(BankAccountNotFoundException::new);
        return snapshotRepository
                .findFirstByCompanyIdAndBankAccountIdOrderByBalanceAsOfDescCreatedAtDesc(companyId, bankAccountId)
                .map(BankBalanceSnapshotResponse::from)
                .orElseThrow(BalanceNotAvailableException::new);
    }

    @GetMapping("/statement-imports")
    public List<StatementImportResponse> statementImports(@PathVariable UUID companyId) {
        return batchRepository.findByCompanyIdOrderByImportedAtDesc(companyId).stream()
                .map(batch -> StatementImportResponse.from(batch, List.of()))
                .toList();
    }

    @GetMapping("/bank-transactions")
    public List<BankTransactionResponse> transactions(
            @PathVariable UUID companyId,
            @RequestParam UUID bankAccountId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        if (to.isBefore(from)) {
            throw new InvalidRequestException("to must not be before from.");
        }
        return transactionRepository.findForCompany(companyId, bankAccountId, null, from, to).stream()
                .map(BankTransactionResponse::from)
                .toList();
    }
}
