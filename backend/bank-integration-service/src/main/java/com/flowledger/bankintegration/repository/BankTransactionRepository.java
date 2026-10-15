package com.flowledger.bankintegration.repository;

import com.flowledger.bankintegration.entity.BankTransaction;
import com.flowledger.bankintegration.entity.TransactionCategory;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

// Every lookup is scoped by company so one company's transaction IDs never resolve for another.
public interface BankTransactionRepository extends JpaRepository<BankTransaction, UUID> {

    Optional<BankTransaction> findByCompanyIdAndId(UUID companyId, UUID id);

    // Serializes classification changes to one transaction, so audit history stays in order.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from BankTransaction t where t.companyId = :companyId and t.id = :id")
    Optional<BankTransaction> lockByCompanyIdAndId(UUID companyId, UUID id);

    // All filters are optional. coalesce rather than ":x is null" because PostgreSQL cannot
    // infer the type of a bare null parameter.
    @Query("""
            select t from BankTransaction t
            where t.companyId = :companyId
              and t.bankAccountId = coalesce(:bankAccountId, t.bankAccountId)
              and t.category = coalesce(:category, t.category)
              and t.transactionDate >= coalesce(:from, t.transactionDate)
              and t.transactionDate <= coalesce(:to, t.transactionDate)
            order by t.transactionDate desc, t.entrySequence desc
            """)
    List<BankTransaction> findForCompany(UUID companyId, UUID bankAccountId, TransactionCategory category,
                                         LocalDate from, LocalDate to);
}
