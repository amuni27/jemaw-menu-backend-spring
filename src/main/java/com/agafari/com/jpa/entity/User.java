package com.agafari.com.jpa.entity;

import com.agafari.com.enums.UserRole;
import com.agafari.com.enums.UserStatus;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;


@Data
@Entity
@Table(name = "users")
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class User extends AuditableEntity {

    @Id
    @Column(nullable = false)
    private String id;

    @Column(name = "fullName", nullable = false)
    private String fullName;

    @Column(nullable = false, unique = true)
    private String email;

    @Column(name = "phoneNumber")
    private String phoneNumber;

    @Column(name = "passwordHash", nullable = false)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private UserRole role;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private UserStatus status = UserStatus.ACTIVE;

    /** AGF-S2-22: when the owner verified their email; null = not verified (Flyway V10). */
    @Column(name = "email_verified_at")
    private java.time.Instant emailVerifiedAt;

    // businessId is nullable (on delete set null)
    @OneToOne(mappedBy = "owner", cascade = CascadeType.ALL, orphanRemoval = true)
    private Business business;

    // One business owner (unique ownerUserId) -> handled in Business side

}
