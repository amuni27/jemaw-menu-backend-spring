package com.agafari.com.dto.request;

import java.util.List;

/** AGF-S2-36: the schedule and note fields shared by dish and category special requests (PATCH semantics). */
public interface ScheduleInput {
    String getNote();
    String getNoteAm();
    String getStartsOn();
    String getEndsOn();
    List<String> getDaysOfWeek();
    String getStartTime();
    String getEndTime();
    Boolean getPaused();
    Boolean getClearStartsOn();
    Boolean getClearEndsOn();
    Boolean getClearTimeWindow();
}
