package com.flowledger.identity.repository;

import com.flowledger.identity.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;
import java.util.UUID;

// Email lookups use lower() to match the uq_users_email_lower unique index, so they can use it.
public interface UserRepository extends JpaRepository<User, UUID> {

    @Query("select count(u) > 0 from User u where lower(u.email) = lower(:email)")
    boolean existsByEmailIgnoreCase(String email);

    @Query("select u from User u where lower(u.email) = lower(:email)")
    Optional<User> findByEmailIgnoreCase(String email);
}
