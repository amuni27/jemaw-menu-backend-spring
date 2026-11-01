package com.agafari.com.jpa.entity;

import com.agafari.com.enums.MonetizationModel;
import com.agafari.com.enums.MonetizationSetting;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;
import org.springframework.data.domain.Persistable;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * AGF E4-M2: one version of the platform monetization configuration (Flyway V12). Insert-only: never updated or deleted
 * (DB trigger). Always new, so saving never merges into an existing version: a second insert of the same version fails
 * on the primary key, which is how concurrent saves are detected.
 */
@Getter
@NoArgsConstructor
@Entity
@Immutable
@Table(name = "monetization_config")
public class MonetizationConfig implements Persistable<Integer> {

    @Id
    @Column(name = "version", nullable = false)
    private Integer version;

    @Enumerated(EnumType.STRING)
    @Column(name = "setting", nullable = false, length = 16)
    private MonetizationSetting setting;

    @Column(name = "model_2_enabled", nullable = false)
    private boolean model2Enabled;

    @Column(name = "model_3_enabled", nullable = false)
    private boolean model3Enabled;

    @Column(name = "model_4_enabled", nullable = false)
    private boolean model4Enabled;

    @Column(name = "effective_from", nullable = false)
    private Instant effectiveFrom;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "created_by_user_id", nullable = false)
    private String createdByUserId;

    /** Setting 1 ignores {@code models} (Model 1 is implied); callers validate first. */
    public MonetizationConfig(int version, MonetizationSetting setting, Set<MonetizationModel> models, Instant now, String createdByUserId) {
        boolean setting2 = setting == MonetizationSetting.SETTING_2;
        this.version = version;
        this.setting = setting;
        this.model2Enabled = setting2 && models.contains(MonetizationModel.MODEL_2);
        this.model3Enabled = setting2 && models.contains(MonetizationModel.MODEL_3);
        this.model4Enabled = setting2 && models.contains(MonetizationModel.MODEL_4);
        this.effectiveFrom = now; // D-M2-2: effective immediately, never backdated
        this.createdAt = now;
        this.createdByUserId = createdByUserId;
    }

    /** The models this version enables, in model order. */
    public List<MonetizationModel> models() {
        if (setting == MonetizationSetting.SETTING_1) return List.of(MonetizationModel.MODEL_1);
        List<MonetizationModel> models = new ArrayList<>();
        if (model2Enabled) models.add(MonetizationModel.MODEL_2);
        if (model3Enabled) models.add(MonetizationModel.MODEL_3);
        if (model4Enabled) models.add(MonetizationModel.MODEL_4);
        return models;
    }

    /** "SETTING_2 [MODEL_2, MODEL_4]": used in audit details. */
    public String describe() {
        return setting + " " + models();
    }

    @Override
    public Integer getId() {
        return version;
    }

    @Override
    public boolean isNew() {
        return true;
    }
}
