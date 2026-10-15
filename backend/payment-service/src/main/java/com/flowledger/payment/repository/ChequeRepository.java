package com.flowledger.payment.repository;

import com.flowledger.payment.entity.Cheque;
import com.flowledger.payment.entity.ChequeStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

// Every lookup is scoped by company so one company's cheque IDs never resolve for another.
public interface ChequeRepository extends JpaRepository<Cheque, UUID> {

    Optional<Cheque> findByCompanyIdAndId(UUID companyId, UUID id);

    boolean existsByCompanyIdAndBankAccountIdAndChequeNumber(UUID companyId, UUID bankAccountId, String chequeNumber);

    // All filters are optional. coalesce rather than ":x is null" because PostgreSQL cannot
    // infer the type of a bare null parameter.
    @Query("""
            select c from Cheque c
            where c.companyId = :companyId
              and c.status = coalesce(:status, c.status)
              and c.expectedClearingDate >= coalesce(:clearingFrom, c.expectedClearingDate)
              and c.expectedClearingDate <= coalesce(:clearingTo, c.expectedClearingDate)
            order by c.expectedClearingDate, c.chequeNumber
            """)
    List<Cheque> findForCompany(UUID companyId, ChequeStatus status, LocalDate clearingFrom, LocalDate clearingTo);
}
