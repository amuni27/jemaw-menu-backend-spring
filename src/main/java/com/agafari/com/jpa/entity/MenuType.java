package com.agafari.com.jpa.entity;

import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

@Data
@Entity
@Table(name = "menu_type")
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class MenuType extends AuditableEntity {

    @Id
    @Column(nullable = false)
    private String id;

    @Column(nullable = false, unique = true)
    private String name;
}

