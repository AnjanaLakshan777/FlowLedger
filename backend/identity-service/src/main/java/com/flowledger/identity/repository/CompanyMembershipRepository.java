package com.flowledger.identity.repository;

import com.flowledger.identity.entity.CompanyMembership;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface CompanyMembershipRepository extends JpaRepository<CompanyMembership, UUID> {
}
