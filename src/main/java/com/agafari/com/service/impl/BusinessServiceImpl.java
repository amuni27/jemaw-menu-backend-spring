package com.agafari.com.service.impl;

import com.agafari.com.dto.request.BusinessHourDto;
import com.agafari.com.dto.request.UpdateBusinessHoursRequest;
import com.agafari.com.dto.response.BusinessHoursResponse;
import com.agafari.com.enums.DayOfWeekEnum;
import com.agafari.com.exception.BadRequestException;
import com.agafari.com.exception.NotFoundException;
import com.agafari.com.jpa.entity.Business;
import com.agafari.com.jpa.entity.BusinessHours;
import com.agafari.com.jpa.repository.BusinessHoursRepository;
import com.agafari.com.jpa.repository.BusinessRepository;
import com.agafari.com.security.CurrentUser;
import com.agafari.com.service.BusinessService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
@Slf4j
public class BusinessServiceImpl implements BusinessService {

    private final CurrentUser currentUser;
    private final BusinessRepository businessRepo;
    private final BusinessHoursRepository hoursRepo;

    private static final List<DayOfWeekEnum> DAYS = List.of(
            DayOfWeekEnum.MONDAY, DayOfWeekEnum.TUESDAY, DayOfWeekEnum.WEDNESDAY,
            DayOfWeekEnum.THURSDAY, DayOfWeekEnum.FRIDAY, DayOfWeekEnum.SATURDAY, DayOfWeekEnum.SUNDAY
    );
    private static final Pattern HHMM = Pattern.compile("^([01]\\d|2[0-3]):([0-5]\\d)$");

    @Override
    @Transactional(readOnly = true)
    public List<BusinessHoursResponse> getBusinessHours(String businessId) {

        log.info("Getting business hours for businessId: {}", businessId);
        currentUser.me();

        Business business = businessRepo.findById(businessId)
                .orElseThrow(() -> new NotFoundException("No business hour found"));

        List<BusinessHoursResponse> hours = business.getBusinessHours().stream()
                .map(BusinessHoursResponse::from)
                .toList();

        if (hours.isEmpty()) {
            throw new NotFoundException("No business hour found");
        }

        return hours;
    }

    @Override
    @Transactional
    public List<BusinessHoursResponse> updateBusinessHours(String businessId, UpdateBusinessHoursRequest request) {
        log.info("Updating business hours for businessId: {}", businessId);

        String currentBusinessId = currentUser.businessId();
        if (!businessId.equals(currentBusinessId)) {
            throw new BadRequestException("Not allowed");
        }

        Business business = businessRepo.findById(businessId)
                .orElseThrow(() -> new NotFoundException("No business hour found"));

        validateHours(request.isOpen24_7(), request.getBusinessHours());

        hoursRepo.deleteAll(business.getBusinessHours());
        hoursRepo.flush();

        List<BusinessHours> newHours = buildHours(business, request.isOpen24_7(), request.getBusinessHours());
        hoursRepo.saveAll(newHours);

        business.setOpen24_7(request.isOpen24_7());
        businessRepo.save(business);

        log.info("Business hours updated for businessId: {}", businessId);

        return newHours.stream().map(BusinessHoursResponse::from).toList();
    }

    private void validateHours(boolean open24_7, List<BusinessHourDto> hours) {
        if (open24_7) return;

        if (hours == null || hours.size() != 7) {
            throw new BadRequestException("businessHours must contain exactly 7 entries when open24_7 is false");
        }

        Set<DayOfWeekEnum> unique = new HashSet<>();
        int openDays = 0;

        for (BusinessHourDto h : hours) {
            unique.add(h.getDayOfWeek());
            if (h.isOpen()) openDays++;

            if (!h.isOpen()) {
                if (h.getStartTime() != null || h.getEndTime() != null) {
                    throw new BadRequestException(h.getDayOfWeek() + ": startTime/endTime must be null when closed");
                }
                continue;
            }

            if (h.getStartTime() == null || h.getEndTime() == null) {
                throw new BadRequestException(h.getDayOfWeek() + ": startTime and endTime are required when open");
            }
            if (!HHMM.matcher(h.getStartTime()).matches() || !HHMM.matcher(h.getEndTime()).matches()) {
                throw new BadRequestException("Invalid time format (HH:mm) for " + h.getDayOfWeek());
            }
            if (timeToMinutes(h.getStartTime()) >= timeToMinutes(h.getEndTime())) {
                throw new BadRequestException(h.getDayOfWeek() + ": startTime must be before endTime");
            }
        }

        if (unique.size() != 7) {
            throw new BadRequestException("businessHours must include exactly one entry for each day (MONDAY..SUNDAY)");
        }
        if (openDays == 0) {
            throw new BadRequestException("At least one day must be open");
        }
    }

    private List<BusinessHours> buildHours(Business biz, boolean open24_7, List<BusinessHourDto> hours) {
        List<BusinessHours> rows = new ArrayList<>();

        if (open24_7) {
            for (DayOfWeekEnum day : DAYS) {
                BusinessHours bh = new BusinessHours();
                bh.setId(UUID.randomUUID().toString());
                bh.setBusiness(biz);
                bh.setDayOfWeek(day);
                bh.setOpen(true);
                bh.setStartTime(null);
                bh.setEndTime(null);
                rows.add(bh);
            }
            return rows;
        }

        for (BusinessHourDto h : hours) {
            BusinessHours bh = new BusinessHours();
            bh.setId(UUID.randomUUID().toString());
            bh.setBusiness(biz);
            bh.setDayOfWeek(h.getDayOfWeek());
            bh.setOpen(h.isOpen());
            bh.setStartTime(h.isOpen() ? h.getStartTime() : null);
            bh.setEndTime(h.isOpen() ? h.getEndTime() : null);
            rows.add(bh);
        }
        return rows;
    }

    private static int timeToMinutes(String t) {
        return Integer.parseInt(t.substring(0, 2)) * 60 + Integer.parseInt(t.substring(3, 5));
    }
}