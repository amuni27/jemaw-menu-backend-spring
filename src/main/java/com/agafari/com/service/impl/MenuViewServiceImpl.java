package com.agafari.com.service.impl;

import com.agafari.com.dto.response.MenuViewsResponse;
import com.agafari.com.exception.BadRequestException;
import com.agafari.com.exception.NotFoundException;
import com.agafari.com.jpa.entity.Business;
import com.agafari.com.jpa.repository.BusinessRepository;
import com.agafari.com.security.CurrentUser;
import com.agafari.com.service.MenuViewCounter;
import com.agafari.com.service.TimeZoneRules;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** AGF-S2-24: the owner's own daily menu views (business-id path endpoint: another business is 403, S1-06). */
@Service
@RequiredArgsConstructor
public class MenuViewServiceImpl {

    public static final int DEFAULT_DAYS = 30;
    public static final int MAX_DAYS = 90;

    private final BusinessRepository businesses;
    private final CurrentUser currentUser;
    private final MenuViewCounter counter;
    private final JdbcTemplate jdbc;

    public MenuViewsResponse views(String businessId, int days) {
        currentUser.requireOwnBusiness(businessId);
        if (days < 1 || days > MAX_DAYS) {
            throw new BadRequestException("days must be between 1 and " + MAX_DAYS);
        }
        Business business = businesses.findById(businessId).orElseThrow(() -> new NotFoundException("Business not found"));
        String zone = TimeZoneRules.zoneId(business.getTimeZone()).getId();
        LocalDate today = counter.today(zone);
        LocalDate first = today.minusDays(days - 1L);

        Map<LocalDate, Integer> stored = new HashMap<>();
        jdbc.query("select view_date, view_count from menu_view_daily where business_id = ? and view_date between ? and ?",
                rs -> { stored.put(rs.getObject(1, LocalDate.class), rs.getInt(2)); }, businessId, first, today);

        List<MenuViewsResponse.Day> series = new ArrayList<>(days);
        for (LocalDate d = first; !d.isAfter(today); d = d.plusDays(1)) {
            series.add(new MenuViewsResponse.Day(d, stored.getOrDefault(d, 0)));
        }
        return new MenuViewsResponse(zone, stored.getOrDefault(today, 0), series);
    }
}
