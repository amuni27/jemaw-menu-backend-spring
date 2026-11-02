package com.agafari.com.dto.response;

import java.util.Map;

/** AGF E4-M6: what a configuration would grandfather, read-only (the save dialog's preview; nothing is written). */
public record GrandfatheringPreview(int grandfathered, Map<String, Integer> byPlan, int overdue, int openEndedWithoutDate,
                                    int transitionDatesNotApplying) {
}
