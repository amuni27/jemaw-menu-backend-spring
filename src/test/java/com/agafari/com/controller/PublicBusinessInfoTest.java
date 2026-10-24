package com.agafari.com.controller;

import com.agafari.com.jpa.entity.BusinessHours;
import com.agafari.com.enums.DayOfWeekEnum;
import com.agafari.com.jpa.repository.BusinessHoursRepository;
import com.agafari.com.jpa.repository.BusinessRepository;
import com.agafari.com.support.AbstractIntegrationTest;
import com.agafari.com.support.TestDataFactory.Tenant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** AGF-S2-03: guests get the restaurant's opening hours, 24/7 flag, time zone (D3) and contact details. */
class PublicBusinessInfoTest extends AbstractIntegrationTest {

    @Autowired private BusinessRepository businesses;
    @Autowired private BusinessHoursRepository hoursRepo;

    private void hour(Tenant t, DayOfWeekEnum day, String start, String end) {
        BusinessHours row = new BusinessHours();
        row.setId(UUID.randomUUID().toString());
        row.setBusiness(t.business());
        row.setDayOfWeek(day);
        row.setOpen(start != null);
        row.setStartTime(start);
        row.setEndTime(end);
        hoursRepo.save(row);
    }

    private void setOpen24_7(Tenant t, boolean value) {
        var b = businesses.findById(t.businessId()).orElseThrow();
        b.setOpen24_7(value);
        businesses.save(b);
    }

    @Test
    void guestMenuCarriesTheWeeklyHoursInDayOrderWithoutInternalIds() throws Exception {
        Tenant owner = data.tenant("alpha");
        data.menu(owner, "Lunch");
        // saved out of order on purpose
        hour(owner, DayOfWeekEnum.SUNDAY, null, null);
        hour(owner, DayOfWeekEnum.WEDNESDAY, "08:00", "22:00");
        hour(owner, DayOfWeekEnum.MONDAY, "08:00", "22:00");
        hour(owner, DayOfWeekEnum.SATURDAY, "10:00", "23:59");
        hour(owner, DayOfWeekEnum.TUESDAY, "08:00", "22:00");
        hour(owner, DayOfWeekEnum.FRIDAY, "08:00", "23:00");
        hour(owner, DayOfWeekEnum.THURSDAY, "08:00", "22:00");

        mockMvc.perform(get("/venue/{id}/menu", owner.businessId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.business.open24_7").value(false))
                .andExpect(jsonPath("$.business.timeZone").value("Africa/Addis_Ababa"))
                .andExpect(jsonPath("$.business.hours", hasSize(7)))
                .andExpect(jsonPath("$.business.hours[*].dayOfWeek").value(contains(
                        "MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY", "SUNDAY")))
                .andExpect(jsonPath("$.business.hours[0].isOpen").value(true))
                .andExpect(jsonPath("$.business.hours[0].startTime").value("08:00"))
                .andExpect(jsonPath("$.business.hours[0].endTime").value("22:00"))
                .andExpect(jsonPath("$.business.hours[5].endTime").value("23:59"))
                .andExpect(jsonPath("$.business.hours[6].isOpen").value(false))
                .andExpect(jsonPath("$.business.hours[6].startTime").doesNotExist())
                .andExpect(jsonPath("$.business.hours[0].id").doesNotExist())
                .andExpect(jsonPath("$.business.hours[0].business").doesNotExist());
    }

    @Test
    void contactDetailsAreAvailableToGuests() throws Exception {
        Tenant owner = data.tenant("alpha");
        data.menu(owner, "Lunch");
        var b = businesses.findById(owner.businessId()).orElseThrow();
        b.setBusinessPhone("+251 11 555 0100");
        b.setStreetAddress("Bole Road 1");
        b.setCity("Addis Ababa");
        businesses.save(b);

        mockMvc.perform(get("/venue/{id}/menu", owner.businessId()))
                .andExpect(jsonPath("$.business.businessPhone").value("+251 11 555 0100"))
                .andExpect(jsonPath("$.business.streetAddress").value("Bole Road 1"))
                .andExpect(jsonPath("$.business.city").value("Addis Ababa"))
                // the owner's personal details stay private
                .andExpect(content().string(not(containsString(owner.owner().getEmail()))))
                .andExpect(content().string(not(containsString("\"owner\""))));
    }

    @Test
    void openAllDayBusinessesAreFlagged() throws Exception {
        Tenant owner = data.tenant("alpha");
        data.menu(owner, "Lunch");
        setOpen24_7(owner, true);

        mockMvc.perform(get("/venue/{id}/menu", owner.businessId()))
                .andExpect(jsonPath("$.business.open24_7").value(true));
    }

    @Test
    void businessesWithoutStoredHoursGetAnEmptyList() throws Exception {
        Tenant owner = data.tenant("alpha");
        data.menu(owner, "Lunch");

        mockMvc.perform(get("/venue/{id}/menu", owner.businessId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.business.hours", hasSize(0)));
    }

    @Test
    void hoursArePresentEvenWithoutPublicMenus() throws Exception {
        Tenant owner = data.tenant("alpha");
        data.hours(owner);

        mockMvc.perform(get("/venue/{id}/menu", owner.businessId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.menus", hasSize(0)))
                .andExpect(jsonPath("$.business.hours", hasSize(7)));
    }

    @Test
    void eachBusinessShowsOnlyItsOwnHours() throws Exception {
        Tenant a = data.tenant("alpha");
        Tenant b = data.tenant("bravo");
        data.menu(a, "Lunch");
        hour(a, DayOfWeekEnum.MONDAY, "08:00", "22:00");
        hour(b, DayOfWeekEnum.MONDAY, "06:15", "11:45");

        mockMvc.perform(get("/venue/{id}/menu", a.businessId()))
                .andExpect(jsonPath("$.business.hours", hasSize(1)))
                .andExpect(content().string(not(containsString("06:15"))));
    }
}
