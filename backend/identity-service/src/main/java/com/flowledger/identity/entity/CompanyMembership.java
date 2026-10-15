package com.flowledger.identity.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CurrentTimestamp;
import org.hibernate.annotations.Generated;
import org.hibernate.annotations.SourceType;
import org.hibernate.generator.EventType;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "company_memberships")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // Required by JPA
public class CompanyMembership {

    @Id
    @Generated
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "company_id", nullable = false, updatable = false)
    private Company company;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, updatable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(name = "company_role", nullable = false, length = 30)
    private CompanyRole companyRole;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private MembershipStatus status;

    @Generated
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    // Memberships change role and status, so the timestamp is set by the database on every write.
    @CurrentTimestamp(event = {EventType.INSERT, EventType.UPDATE}, source = SourceType.DB)
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public CompanyMembership(Company company, User user, CompanyRole companyRole, MembershipStatus status) {
        this.company = company;
        this.user = user;
        this.companyRole = companyRole;
        this.status = status;
    }

    // Access needs the membership, the user's account and the company to be active.
    public boolean grantsCompanyAccess() {
        return status == MembershipStatus.ACTIVE
                && user.getAccountStatus() == AccountStatus.ACTIVE
                && company.getStatus() == CompanyStatus.ACTIVE;
    }

    public boolean isActiveCompanyAdmin() {
        return status == MembershipStatus.ACTIVE && companyRole == CompanyRole.COMPANY_ADMIN;
    }

    public void changeRole(CompanyRole companyRole) {
        this.companyRole = companyRole;
    }

    public void changeStatus(MembershipStatus status) {
        this.status = status;
    }
}
