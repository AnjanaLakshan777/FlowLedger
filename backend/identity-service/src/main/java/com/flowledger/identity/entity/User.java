package com.flowledger.identity.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Generated;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "users")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // Required by JPA
public class User {

    @Id
    @Generated
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "email", nullable = false, length = 320)
    private String email;

    @Column(name = "full_name", nullable = false, length = 160)
    private String fullName;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "account_status", nullable = false, length = 20)
    private AccountStatus accountStatus;

    // Read-only for the application: new users get the database default USER, and no code path
    // writes it, so the API can never grant SYSTEM_ADMIN.
    @Generated
    @Enumerated(EnumType.STRING)
    @Column(name = "platform_role", nullable = false, length = 30, updatable = false)
    private PlatformRole platformRole;

    @Generated
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Generated
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public User(String email, String fullName, String passwordHash, AccountStatus accountStatus) {
        this.email = email;
        this.fullName = fullName;
        this.passwordHash = passwordHash;
        this.accountStatus = accountStatus;
    }

    public boolean isActivePlatformAdmin() {
        return accountStatus == AccountStatus.ACTIVE && platformRole == PlatformRole.SYSTEM_ADMIN;
    }
}
