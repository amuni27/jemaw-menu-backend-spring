package com.agafari.com.service;

import com.agafari.com.dto.request.MonetizationConfigRequest;
import com.agafari.com.dto.response.MonetizationConfigHistoryPage;
import com.agafari.com.dto.response.MonetizationConfigResponse;
import com.agafari.com.enums.MonetizationModel;
import com.agafari.com.enums.MonetizationSetting;
import com.agafari.com.exception.ConfigVersionConflictException;
import com.agafari.com.exception.FieldValidationException;
import com.agafari.com.jpa.entity.MonetizationConfig;
import com.agafari.com.jpa.repository.MonetizationConfigRepository;
import com.agafari.com.jpa.repository.UserRepository;
import com.agafari.com.security.AdminAuditService;
import com.agafari.com.security.LogMasking;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * AGF E4-M2: the platform monetization configuration (R1–R3). The Super Admin picks Setting 1 (Model 1 only) or Setting 2
 * (any nonempty combination of Models 2–4); each change is a new version, effective immediately and audited together with
 * the change. Rejected changes are audited too. Nothing outside the configuration and the audit table is written, so
 * restaurants and (future) subscriptions are never changed here: grandfathering (R7) is applied by E4-M6.
 */
@Service
public class MonetizationConfigService {

    public static final String CHANGED = "MONETIZATION_CONFIG_CHANGED";
    public static final String CHANGE_REJECTED = "MONETIZATION_CONFIG_CHANGE_REJECTED";
    private static final String TARGET = "MONETIZATION_CONFIG";

    private final MonetizationConfigRepository configs;
    private final UserRepository users;
    private final AdminAuditService audit;
    private final TransactionTemplate transaction;
    private final MonetizationLocks locks;
    private final Clock clock = Clock.systemUTC();

    public MonetizationConfigService(MonetizationConfigRepository configs, UserRepository users, AdminAuditService audit,
                                     PlatformTransactionManager transactionManager, MonetizationLocks locks) {
        this.configs = configs;
        this.users = users;
        this.audit = audit;
        this.locks = locks;
        this.transaction = new TransactionTemplate(transactionManager); // the version and its audit row commit together
    }

    public MonetizationConfigResponse current() {
        return configs.findTopByOrderByVersionDesc().map(this::response).orElseGet(MonetizationConfigResponse::notConfigured);
    }

    public MonetizationConfigHistoryPage history(int page, int size) {
        var result = configs.findAllByOrderByVersionDesc(PageRequest.of(page, size));
        return new MonetizationConfigHistoryPage(result.getContent().stream().map(this::response).toList(), page, size,
                result.getTotalElements());
    }

    public MonetizationConfigResponse change(MonetizationConfigRequest request, String adminId, String adminEmail) {
        String actor = LogMasking.maskEmail(adminEmail);
        Change change;
        try {
            change = validate(request);
        } catch (FieldValidationException e) {
            audit.failure(CHANGE_REJECTED, adminId, actor, "invalid");
            throw e;
        }
        try {
            return transaction.execute(status -> apply(change, adminId, actor));
        } catch (ConfigVersionConflictException e) {
            audit.failure(CHANGE_REJECTED, adminId, actor, "version_conflict");
            throw e;
        } catch (DataIntegrityViolationException e) {
            // A concurrent save inserted the same version first (primary key); anything else is a real error
            if (currentVersion() <= change.expectedVersion()) throw e;
            audit.failure(CHANGE_REJECTED, adminId, actor, "version_conflict");
            throw new ConfigVersionConflictException();
        }
    }

    private MonetizationConfigResponse apply(Change change, String adminId, String actor) {
        locks.configExclusive(); // E4-M4 §16.2: waits for running subscription/request writes, and they wait for this change
        MonetizationConfig current = configs.findTopByOrderByVersionDesc().orElse(null);
        int currentVersion = current == null ? 0 : current.getVersion();
        if (change.expectedVersion() != currentVersion) throw new ConfigVersionConflictException();
        if (current != null && current.getSetting() == change.setting() && current.models().equals(change.models())) {
            return response(current); // D-M2-5: unchanged, so no new version and no audit
        }

        MonetizationConfig saved = configs.saveAndFlush(new MonetizationConfig(currentVersion + 1, change.setting(),
                EnumSet.copyOf(change.models()), clock.instant(), adminId));
        audit.success(CHANGED, adminId, actor, TARGET, String.valueOf(saved.getVersion()),
                saved.describe() + " (was " + (current == null ? "not configured" : current.describe()) + ")");
        return response(saved);
    }

    private int currentVersion() {
        return configs.findTopByOrderByVersionDesc().map(MonetizationConfig::getVersion).orElse(0);
    }

    /** The checked request: setting, models in model order (Model 1 for Setting 1) and the version the admin started from. */
    private record Change(MonetizationSetting setting, List<MonetizationModel> models, int expectedVersion) {
    }

    private static Change validate(MonetizationConfigRequest request) {
        Map<String, String> errors = new LinkedHashMap<>();
        MonetizationSetting setting = parse(MonetizationSetting.class, request.setting());
        if (setting == null) errors.put("setting", "Choose Setting 1 or Setting 2");

        Set<MonetizationModel> models = EnumSet.noneOf(MonetizationModel.class);
        List<String> raw = request.models() == null ? List.of() : request.models();
        for (String value : raw) {
            MonetizationModel model = parse(MonetizationModel.class, value);
            if (model == null) {
                errors.putIfAbsent("models", "Unknown model");
            } else if (!models.add(model)) {
                errors.putIfAbsent("models", "Each model can be chosen only once");
            }
        }
        if (setting == MonetizationSetting.SETTING_1 && !EnumSet.of(MonetizationModel.MODEL_1).containsAll(models)) {
            errors.putIfAbsent("models", "Setting 1 is Model 1 only");
        }
        if (setting == MonetizationSetting.SETTING_2) {
            if (models.contains(MonetizationModel.MODEL_1)) errors.putIfAbsent("models", "Model 1 belongs to Setting 1");
            if (models.isEmpty()) errors.putIfAbsent("models", "Choose at least one of Models 2, 3 and 4");
        }
        if (request.expectedVersion() == null || request.expectedVersion() < 0) {
            errors.put("expectedVersion", "Reload the configuration and try again");
        }
        if (!errors.isEmpty()) throw new FieldValidationException(errors);

        List<MonetizationModel> ordered = setting == MonetizationSetting.SETTING_1
                ? List.of(MonetizationModel.MODEL_1) : List.copyOf(EnumSet.copyOf(models));
        return new Change(setting, ordered, request.expectedVersion());
    }

    private static <E extends Enum<E>> E parse(Class<E> type, String value) {
        if (value == null) return null;
        for (E constant : type.getEnumConstants()) if (constant.name().equals(value)) return constant;
        return null;
    }

    private MonetizationConfigResponse response(MonetizationConfig config) {
        String changedBy = users.findById(config.getCreatedByUserId()).map(u -> LogMasking.maskEmail(u.getEmail())).orElse("unknown");
        return new MonetizationConfigResponse(true, config.getVersion(), config.getSetting().name(),
                config.models().stream().map(Enum::name).toList(), config.getEffectiveFrom(), config.getCreatedAt(), changedBy);
    }
}
