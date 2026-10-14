package com.flowledger.bankintegration.repository;

import com.flowledger.bankintegration.entity.BankAccount;
import com.flowledger.bankintegration.entity.BankAccountStatus;
import com.flowledger.bankintegration.entity.BankAccountType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

// Every lookup is scoped by company so one company's account IDs never resolve for another.
public interface BankAccountRepository extends JpaRepository<BankAccount, UUID> {

    Optional<BankAccount> findByCompanyIdAndId(UUID companyId, UUID id);

    List<BankAccount> findByCompanyIdOrderByCreatedAtAsc(UUID companyId);

    boolean existsByCompanyIdAndAccountTypeAndStatus(UUID companyId, BankAccountType accountType,
                                                      BankAccountStatus status);
}
