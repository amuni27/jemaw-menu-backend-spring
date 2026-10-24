package com.agafari.com.controller;

import com.agafari.com.enums.DayOfWeekEnum;
import com.agafari.com.jpa.entity.BusinessHours;
import com.agafari.com.jpa.repository.BusinessHoursRepository;
import com.agafari.com.support.AbstractIntegrationTest;
import com.agafari.com.support.TestDataFactory.Tenant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AGF-S2-03 (found during the demo check): editing hours must replace the week, not add to it,
 * so guests see the current hours. Duplicates stored by the old behavior are resolved to the newest row per day.
 */
class BusinessHoursUpdateTest extends AbstractIntegrationTest {

    @Autowired private BusinessHoursRepository hoursRepo;

    private static String week(String saturdayStart, String saturdayEnd) {
        StringBuilder json = new StringBuilder("{\"open24_7\":false,\"businessHours\":[");
        String[] days = {"MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY", "SUNDAY"};
        for (int i = 0; i < days.length; i++) {
            if (i > 0) json.append(',');
            boolean saturday = days[i].equals("SATURDAY");
            boolean open = !days[i].equals("SUNDAY") && (!saturday || saturdayStart != null);
            String start = saturday ? saturdayStart : "08:00";
            String end = saturday ? saturdayEnd : "22:00";
            json.append(open
                    ? "{\"dayOfWeek\":\"" + days[i] + "\",\"isOpen\":true,\"startTime\":\"" + start + "\",\"endTime\":\"" + end + "\"}"
                    : "{\"dayOfWeek\":\"" + days[i] + "\",\"isOpen\":false,\"startTime\":null,\"endTime\":null}");
        }
        return json.append("]}").toString();
    }

    private long rowsFor(Tenant t) {
        return hoursRepo.findAll().stream().filter(h -> h.getBusiness().getId().equals(t.businessId())).count();
    }

    private void edit(Tenant t, String body) throws Exception {
        mockMvc.perform(patch("/api/business/{id}/hours", t.businessId()).header("Authorization", data.bearer(t))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
    }

    @Test
    void editingHoursReplacesThePreviousWeek() throws Exception {
        Tenant owner = data.tenant("alpha");
        data.hours(owner); // 7 rows, 09:00-17:00

        edit(owner, week("08:00", "22:00"));
        edit(owner, week(null, null));          // Saturday closed
        edit(owner, week("10:00", "14:00"));

        assertThat(rowsFor(owner)).isEqualTo(7);
        mockMvc.perform(get("/venue/{id}/menu", owner.businessId()))
                .andExpect(jsonPath("$.business.hours", hasSize(7)))
                .andExpect(jsonPath("$.business.hours[5].startTime").value("10:00"))
                .andExpect(jsonPath("$.business.hours[5].endTime").value("14:00"))
                .andExpect(jsonPath("$.business.hours[6].isOpen").value(false));
        mockMvc.perform(get("/api/business/{id}/hours", owner.businessId()).header("Authorization", data.bearer(owner)))
                .andExpect(jsonPath("$", hasSize(7)));
    }

    @Test
    void switchingTo24HoursAndBackKeepsOneRowPerDay() throws Exception {
        Tenant owner = data.tenant("alpha");
        data.hours(owner);

        edit(owner, "{\"open24_7\":true,\"businessHours\":[]}");
        edit(owner, week("08:00", "22:00"));

        assertThat(rowsFor(owner)).isEqualTo(7);
        mockMvc.perform(get("/venue/{id}/menu", owner.businessId()))
                .andExpect(jsonPath("$.business.open24_7").value(false))
                .andExpect(jsonPath("$.business.hours[5].endTime").value("22:00"));
    }

    @Test
    void guestsSeeTheNewestRowWhenOldDuplicatesAreStored() throws Exception {
        Tenant owner = data.tenant("alpha");
        data.hours(owner); // older: every day 09:00-17:00
        BusinessHours newer = new BusinessHours();
        newer.setId(UUID.randomUUID().toString());
        newer.setBusiness(owner.business());
        newer.setDayOfWeek(DayOfWeekEnum.MONDAY);
        newer.setOpen(true);
        newer.setStartTime("07:00");
        newer.setEndTime("23:00");
        hoursRepo.save(newer); // a duplicate Monday saved later, as the old update behavior left them

        mockMvc.perform(get("/venue/{id}/menu", owner.businessId()))
                .andExpect(jsonPath("$.business.hours", hasSize(7)))
                .andExpect(jsonPath("$.business.hours[*].dayOfWeek").value(contains(
                        "MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY", "SUNDAY")))
                .andExpect(jsonPath("$.business.hours[0].startTime").value("07:00"))
                .andExpect(jsonPath("$.business.hours[1].startTime").value("09:00"));

        // The next edit cleans the duplicates up
        edit(owner, week("08:00", "22:00"));
        assertThat(rowsFor(owner)).isEqualTo(7);
    }
}
