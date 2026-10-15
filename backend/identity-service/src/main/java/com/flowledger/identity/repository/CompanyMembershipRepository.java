package com.flowledger.identity.repository;

import com.flowledger.identity.entity.CompanyMembership;
import com.flowledger.identity.entity.CompanyRole;
import com.flowledger.identity.entity.MembershipStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
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

    // Scoped by company so another company's membership IDs never resolve.
    @Query("""
            select m from CompanyMembership m
            join fetch m.user
            where m.company.id = :companyId and m.id = :membershipId
            """)
    Optional<CompanyMembership> findInCompany(UUID companyId, UUID membershipId);

    @Query("""
            select m from CompanyMembership m
            join fetch m.user
            where m.company.id = :companyId
            order by m.createdAt
            """)
    List<CompanyMembership> findAllInCompany(UUID companyId);

    // Only memberships the user can act through: active membership in an active company.
    @Query("""
            select m from CompanyMembership m
            join fetch m.company c
            where m.user.id = :userId
              and m.status = com.flowledger.identity.entity.MembershipStatus.ACTIVE
              and c.status = com.flowledger.identity.entity.CompanyStatus.ACTIVE
            order by c.name, c.id
            """)
    List<CompanyMembership> findActiveForUser(UUID userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select m from CompanyMembership m
            where m.company.id = :companyId and m.companyRole = :companyRole and m.status = :status
            """)
    List<CompanyMembership> lockByCompanyAndRoleAndStatus(UUID companyId, CompanyRole companyRole,
                                                         MembershipStatus status);
}
