package com.agafari.com.jpa.entity;

import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.util.ArrayList;
import java.util.List;


@Data
@Entity
@Table(name = "business")
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class Business extends AuditableEntity {

    @Id
    @Column(nullable = false)
    private String id;

    // unique in DB, one-to-one
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ownerUserId", nullable = false)
    private User owner;

    @Column(nullable = false)
    private String name;

    @Column(name = "businessPhone", nullable = false)
    private String businessPhone;

    @Column(name = "streetAddress", nullable = false)
    private String streetAddress;

    @Column(nullable = false)
    private String city;

    @Column(nullable = false)
    private String state;

    @Column(nullable = false)
    private String zipcode;

    @Column(name = "logoKey")
    private String logoKey;

    @Column(name = "customSubdomain", nullable = false, unique = true)
    private String customSubdomain;

    @Column(name = "open24_7", nullable = false)
    private boolean open24_7 = false;

    @OneToMany(mappedBy = "business",fetch = FetchType.EAGER, cascade = CascadeType.ALL, orphanRemoval = true)
    private List<BusinessHours> businessHours = new ArrayList<>();

    @OneToMany(mappedBy = "business", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<Menu> menus = new ArrayList<>();

    @Column(nullable = false)
    private String currency = "ETB";
}

