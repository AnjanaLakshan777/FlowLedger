package com.flowledger.identity.repository;

import com.flowledger.identity.entity.CompanyMembership;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;
import java.util.UUID;

public interface CompanyMembershipRepository extends JpaRepository<CompanyMembership, UUID> {

    @Query("""
            select m from CompanyMembership m
            join fetch m.user
            join fetch m.company
            where m.company.id = :companyId and m.user.id = :userId
            """)
    Optional<CompanyMembership> findWithUserAndCompany(UUID companyId, UUID userId);
}
