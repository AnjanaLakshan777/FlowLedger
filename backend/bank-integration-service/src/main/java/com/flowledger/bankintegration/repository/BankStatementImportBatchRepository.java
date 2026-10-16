package com.flowledger.bankintegration.repository;

import com.flowledger.bankintegration.entity.BankStatementImportBatch;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

// Every lookup is scoped by company so one company's batch IDs never resolve for another.
public interface BankStatementImportBatchRepository extends JpaRepository<BankStatementImportBatch, UUID> {

    Optional<BankStatementImportBatch> findByCompanyIdAndId(UUID companyId, UUID id);

    List<BankStatementImportBatch> findByCompanyIdOrderByImportedAtDesc(UUID companyId);

    // Serializes removal with itself, so a batch is removed once.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from BankStatementImportBatch b where b.companyId = :companyId and b.id = :id")
    Optional<BankStatementImportBatch> lockByCompanyIdAndId(UUID companyId, UUID id);

    // Earlier successful imports of the same file into the same account, for information only.
    @Query("""
            select b.id from BankStatementImportBatch b
            where b.companyId = :companyId and b.bankAccountId = :bankAccountId
              and b.fileSha256 = :fileSha256 and b.id <> :batchId and b.importedAt < :importedAt
              and b.status = com.flowledger.bankintegration.entity.ImportBatchStatus.IMPORTED
            order by b.importedAt
            """)
    List<UUID> findEarlierImportsOfFile(UUID companyId, UUID bankAccountId, String fileSha256, UUID batchId,
                                        Instant importedAt);
}
