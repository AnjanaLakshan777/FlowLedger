package com.flowledger.cashmanagement.repository;

import com.flowledger.cashmanagement.entity.DailySale;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

// Every lookup is scoped by company so one company's record IDs never resolve for another.
public interface DailySaleRepository extends JpaRepository<DailySale, UUID> {

    Optional<DailySale> findByCompanyIdAndId(UUID companyId, UUID id);

    boolean existsByCompanyIdAndSaleDate(UUID companyId, LocalDate saleDate);

    // from and to are optional, inclusive bounds on the sale date. coalesce rather than
    // ":from is null" because PostgreSQL cannot infer the type of a bare null parameter.
    @Query("""
            select s from DailySale s
            where s.companyId = :companyId
              and s.saleDate >= coalesce(:from, s.saleDate)
              and s.saleDate <= coalesce(:to, s.saleDate)
            order by s.saleDate desc
            """)
    List<DailySale> findForCompany(UUID companyId, LocalDate from, LocalDate to);
}
