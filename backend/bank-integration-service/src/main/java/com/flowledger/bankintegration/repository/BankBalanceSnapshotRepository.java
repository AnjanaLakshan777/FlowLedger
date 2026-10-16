package com.flowledger.bankintegration.repository;

import com.flowledger.bankintegration.entity.BankBalanceSnapshot;
import org.springframework.data.repository.Repository;

import java.util.Optional;
import java.util.UUID;

// Read-only: snapshots are written by StatementImportStore and ManualEntryStore.
public interface BankBalanceSnapshotRepository extends Repository<BankBalanceSnapshot, UUID> {

    // The newest balance; of two with the same time, the one recorded last.
    Optional<BankBalanceSnapshot> findFirstByCompanyIdAndBankAccountIdOrderByBalanceAsOfDescCreatedAtDesc(
            UUID companyId, UUID bankAccountId);

    Optional<BankBalanceSnapshot> findById(UUID id);
}
