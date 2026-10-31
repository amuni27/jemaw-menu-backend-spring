package com.agafari.com.jpa.entity;

import java.time.LocalDate;
import java.util.List;

/**
 * AGF-S2-36: the schedule shared by dish specials (S2-25) and category specials: optional dates, weekdays and one
 * daily window in the restaurant's local time, plus pause. Rules: SpecialSchedule, ScheduleRules.
 */
public interface Scheduled {
    LocalDate getStartsOn();
    void setStartsOn(LocalDate value);
    LocalDate getEndsOn();
    void setEndsOn(LocalDate value);
    List<String> getDaysOfWeek();
    void setDaysOfWeek(List<String> value);
    String getStartTime();
    void setStartTime(String value);
    String getEndTime();
    void setEndTime(String value);
    boolean isPaused();
    void setPaused(boolean value);
    String getNote();
    void setNote(String value);
    String getNoteAm();
    void setNoteAm(String value);
}
