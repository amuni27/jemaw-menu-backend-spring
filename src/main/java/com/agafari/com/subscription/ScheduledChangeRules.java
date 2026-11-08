package com.agafari.com.subscription;

import com.agafari.com.enums.MonetizationModel;
import com.agafari.com.jpa.entity.MonetizationConfig;
import com.agafari.com.jpa.entity.ScheduledChange;

/** AGF SUB-R2-11: a due change counts only if it can still take effect. A cancellation always can; a downgrade needs its plan enabled now. */
public final class ScheduledChangeRules {

    private ScheduledChangeRules() {
    }

    public static boolean targetEnabled(String targetPlanCode, MonetizationConfig config) {
        return targetPlanCode == null || (config != null && config.models().contains(MonetizationModel.valueOf(targetPlanCode)));
    }

    public static boolean stillApplies(ScheduledChange change, MonetizationConfig config) {
        return targetEnabled(change.getTargetPlanCode(), config);
    }
}
