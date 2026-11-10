package com.agafari.com.controller;

import com.agafari.com.billing.MarketCalendarService;
import com.agafari.com.dto.request.MarketCalendarYearRequest;
import com.agafari.com.dto.request.MarketHolidayRequest;
import com.agafari.com.dto.response.MarketCalendarView;
import com.agafari.com.security.CurrentUser;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * AGF SUB-R4-12 (R-6, I4, TA §6.9): the Super Admin's per-market business-day calendar ({@code /api/admin/**}: ADMIN only).
 * Every change is audited and recomputes the deadlines of the market's open financial exceptions.
 */
@RestController
@RequestMapping("/api/admin/billing/calendar/{market}")
@RequiredArgsConstructor
public class AdminMarketCalendarController {

    private final CurrentUser currentUser;
    private final MarketCalendarService calendar;

    @GetMapping("/{year}")
    public ResponseEntity<MarketCalendarView> get(@PathVariable String market, @PathVariable int year) {
        return ResponseEntity.ok(calendar.get(market, year));
    }

    @PutMapping("/{year}")
    public ResponseEntity<MarketCalendarView> updateYear(@PathVariable String market, @PathVariable int year, @RequestBody MarketCalendarYearRequest request) {
        return ResponseEntity.ok(calendar.updateYear(market, year, request, currentUser.userId(), currentUser.email()));
    }

    @PostMapping("/holidays")
    public ResponseEntity<MarketCalendarView> addHoliday(@PathVariable String market, @RequestBody MarketHolidayRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(calendar.addHoliday(market, request, currentUser.userId(), currentUser.email()));
    }

    @DeleteMapping("/holidays/{date}")
    public ResponseEntity<MarketCalendarView> removeHoliday(@PathVariable String market, @PathVariable String date) {
        return ResponseEntity.ok(calendar.removeHoliday(market, date, currentUser.userId(), currentUser.email()));
    }
}
