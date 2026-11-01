package com.agafari.com.jpa.repository;

import com.agafari.com.jpa.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Modifying;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User, String> {
    Optional<User> findByEmail(String email);

    /** AGF-S2-13A: changes the hash only if it is still {@code expectedHash}; returns the number of rows changed (0 or 1). */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update User u set u.passwordHash = :newHash where u.id = :id and u.passwordHash = :expectedHash")
    int updatePasswordHashIfUnchanged(@Param("id") String id, @Param("expectedHash") String expectedHash, @Param("newHash") String newHash);

    /**
     * AGF-S2-22: marks the email verified only if it is still {@code email} and not verified yet; returns the number of
     * rows changed (0 or 1). One conditional update, so concurrent verifications change the account exactly once.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update User u set u.emailVerifiedAt = :now where u.id = :id and u.email = :email and u.emailVerifiedAt is null")
    int markEmailVerifiedIfUnverified(@Param("id") String id, @Param("email") String email, @Param("now") java.time.Instant now);

    /** AGF E4-M1: whether any account has this role (the admin bootstrap runs only while no admin exists). */
    boolean existsByRole(com.agafari.com.enums.UserRole role);
}
