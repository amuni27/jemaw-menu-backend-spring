package com.agafari.com.security;

import com.agafari.com.service.CountryRules;
import com.agafari.com.service.TimeZoneRules;
import com.agafari.com.service.GoogleMapsUrls;
import com.agafari.com.dto.request.AuthLoginRequest;
import com.agafari.com.dto.request.AuthRegisterRequest;
import com.agafari.com.dto.request.BusinessHourDto;
import com.agafari.com.dto.request.ChangePasswordRequest;
import com.agafari.com.dto.response.AuthRegisterResponse;
import com.agafari.com.dto.response.AuthTokenResponse;
import com.agafari.com.dto.response.BusinessHoursResponse;
import com.agafari.com.enums.DayOfWeekEnum;
import com.agafari.com.enums.UserRole;
import com.agafari.com.enums.UserStatus;
import com.agafari.com.exception.AccountInactiveException;
import com.agafari.com.service.CurrencyPolicy;
import com.agafari.com.service.LogoUrls;
import com.agafari.com.validation.StrongPasswordValidator;
import com.agafari.com.exception.BadRequestException;
import com.agafari.com.exception.ConflictException;
import com.agafari.com.exception.ForbiddenException;
import com.agafari.com.exception.NotFoundException;
import com.agafari.com.jpa.entity.*;
import com.agafari.com.jpa.repository.BusinessHoursRepository;
import com.agafari.com.jpa.repository.BusinessRepository;
import com.agafari.com.jpa.repository.UserRepository;
import com.agafari.com.service.JwtService;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepo;
    private final BusinessRepository businessRepo;
    private final BusinessHoursRepository hoursRepo;

    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    @Value("${cloudflare.publicBaseUrl}")
    private String publicBaseUrl;

    @Value("${app.baseDomain:agafarimenu.com}")
    private String baseDomain;

    private static final List<DayOfWeekEnum> DAYS = List.of(
            DayOfWeekEnum.MONDAY, DayOfWeekEnum.TUESDAY, DayOfWeekEnum.WEDNESDAY,
            DayOfWeekEnum.THURSDAY, DayOfWeekEnum.FRIDAY, DayOfWeekEnum.SATURDAY, DayOfWeekEnum.SUNDAY
    );

    private static final Pattern HHMM = Pattern.compile("^([01]\\d|2[0-3]):([0-5]\\d)$");
    private static final Pattern SUBDOMAIN = Pattern.compile("^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$");

    private static String normalizeEmail(String email) { return email.trim().toLowerCase(); }
    private static String normalizeSubdomain(String sub) { return sub.trim().toLowerCase(); }

    private void validateSubdomainFormat(String sub) {
        if (!SUBDOMAIN.matcher(sub).matches()) {
            throw new BadRequestException(
                "Invalid subdomain: only lowercase letters, digits, and hyphens are allowed. " +
                "It must not start or end with a hyphen, and dots are not permitted."
            );
        }
    }

    private static int timeToMinutes(String t) {
        int hh = Integer.parseInt(t.substring(0, 2));
        int mm = Integer.parseInt(t.substring(3, 5));
        return hh * 60 + mm;
    }

    @Transactional
    public AuthRegisterResponse register(AuthRegisterRequest req) {
        long start = System.currentTimeMillis();

        String email = normalizeEmail(req.getEmail());
        String subdomainLabel = normalizeSubdomain(req.getCustomSubdomain());
        validateSubdomainFormat(subdomainLabel);
        String subdomain = subdomainLabel + "." + baseDomain;
        String phone = (req.getPhoneNumber() == null || req.getPhoneNumber().trim().isEmpty())
                ? null : req.getPhoneNumber().trim();
        // AGF-S2-12 CR1: validated by @CountryLocationRules; Ethiopia stores no address (empty, D-CR1-2)
        String country = CountryRules.country(req.getCountry());
        boolean us = CountryRules.isUnitedStates(country);

        // Start log (no secrets)
        log.info("event=auth.register.start email={} subdomain={} open24_7={} hasPhone={} city={} state={}",
                LogMasking.maskEmail(email), subdomain, req.isOpen24_7(), phone != null, safe(req.getCity()), safe(req.getState()));

        // Validation log (before validation helps identify bad payloads)
        log.debug("event=auth.register.validate email={} open24_7={} hoursCount={}",
                LogMasking.maskEmail(email), req.isOpen24_7(), (req.getBusinessHours() == null ? 0 : req.getBusinessHours().size()));

        // Node-style validation for businessHours
        validateBusinessHours(req.isOpen24_7(), req.getBusinessHours());

        // Check if email already exists
        if (userRepo.findByEmail(email).isPresent()) {
            log.warn("event=auth.register.email_exists email={}", LogMasking.maskEmail(email));
            throw new BadRequestException("This email is already registered.");
        }

        // Check if subdomain already exists
        if (businessRepo.findByCustomSubdomain(subdomain).isPresent()) {
            log.warn("event=auth.register.subdomain_exists subdomain={}", subdomain);
            throw new BadRequestException("This business link is already registered.");
        }

        try {
            User user = new User();
            user.setId(UUID.randomUUID().toString());
            user.setFullName(req.getFullName().trim());
            user.setEmail(email);
            user.setPhoneNumber(phone);
            user.setPasswordHash(passwordEncoder.encode(req.getPassword())); // DO NOT LOG
            user.setRole(UserRole.VENUE);
            user.setStatus(UserStatus.ACTIVE);

            userRepo.save(user);
            log.info("event=auth.register.user_saved email={} userId={} role={} status={}",
                    LogMasking.maskEmail(email), user.getId(), user.getRole(), user.getStatus());

            Business biz = new Business();
            biz.setId(UUID.randomUUID().toString());
            biz.setOwner(user);
            biz.setName(req.getBusinessName().trim());
            biz.setBusinessPhone(req.getBusinessPhone().trim());
            biz.setCountry(country);
            biz.setStreetAddress(us ? req.getStreetAddress().trim() : "");
            biz.setCity(us ? req.getCity().trim() : "");
            biz.setState(us ? CountryRules.usState(req.getState()) : "");
            biz.setZipcode(us ? req.getZipcode().trim() : "");
            if (us) biz.setCurrency("USD"); // D-CR1-8
            biz.setTimeZone(TimeZoneRules.zoneFor(country, req.getTimeZone())); // AGF-S2-26
            biz.setCustomSubdomain(subdomain);
            biz.setOpen24_7(req.isOpen24_7());
            biz.setLocationUrl(GoogleMapsUrls.normalize(req.getLocationUrl()).orElse(null)); // AGF-S2-12; blank = none

            businessRepo.save(biz);
            log.info("event=auth.register.business_saved email={} userId={} businessId={} subdomain={}",
                    LogMasking.maskEmail(email), user.getId(), biz.getId(), subdomain);

            // create 7 business hours rows
            List<BusinessHours> hours = buildHours(biz, req.isOpen24_7(), req.getBusinessHours());
            hoursRepo.saveAll(hours);

            log.info("event=auth.register.hours_saved email={} businessId={} hoursRows={}",
                    LogMasking.maskEmail(email), biz.getId(), hours.size());

            // JWT (DO NOT LOG token)
            String token = jwtService.signToken(user.getEmail(), user.getId(), biz.getId(), user.getRole().name());

            long ms = System.currentTimeMillis() - start;
            log.info("event=auth.register.success email={} userId={} businessId={} duration_ms={}",
                    LogMasking.maskEmail(email), user.getId(), biz.getId(), ms);

            return new AuthRegisterResponse("Account created", token);

        } catch (DataIntegrityViolationException ex) {
            long ms = System.currentTimeMillis() - start;
            // Keep message stable so Splunk can alert on it
            log.warn("event=auth.register.conflict email={} subdomain={} duration_ms={} errorClass={}",
                    LogMasking.maskEmail(email), subdomain, ms, ex.getClass().getSimpleName());
            throw new ConflictException("This email, phone number, or business link is already registered.");
        } catch (Exception ex) {
            long ms = System.currentTimeMillis() - start;
            log.error("event=auth.register.error email={} subdomain={} duration_ms={} errorClass={}",
                    LogMasking.maskEmail(email), subdomain, ms, ex.getClass().getSimpleName(), ex);
            throw ex;
        }
    }

    public AuthTokenResponse login(AuthLoginRequest req) {
        long start = System.currentTimeMillis();

        String email = normalizeEmail(req.getEmail());
        log.info("event=auth.login.start email={}", LogMasking.maskEmail(email));

        User user = userRepo.findByEmail(email)
                .orElseThrow(() -> {
                    log.warn("event=auth.login.invalid_email email={}", LogMasking.maskEmail(email));
                    return new BadRequestException("Invalid credentials");
                });

        if (StrongPasswordValidator.exceedsBcryptLimit(req.getPassword())
                || !passwordEncoder.matches(req.getPassword(), user.getPasswordHash())) {
            log.warn("event=auth.login.invalid_password email={} userId={}", LogMasking.maskEmail(email), user.getId());
            throw new BadRequestException("Invalid credentials");
        }

        // Checked after the password, so the account status is only revealed to someone who knows it
        if (user.getStatus() != UserStatus.ACTIVE) {
            log.warn("event=auth.login.account_inactive userId={} status={}", user.getId(), user.getStatus());
            throw new AccountInactiveException();
        }

        Business biz = businessRepo.findByOwnerId(user.getId())
                .orElseThrow(() -> {
                    log.warn("event=auth.login.business_missing email={} userId={}", LogMasking.maskEmail(email), user.getId());
                    return new ForbiddenException("business not found");
                });

        String token = jwtService.signToken(user.getEmail(), user.getId(), biz.getId(), user.getRole().name());

        long ms = System.currentTimeMillis() - start;
        log.info("event=auth.login.success email={} userId={} businessId={} role={} duration_ms={}",
                LogMasking.maskEmail(email), user.getId(), biz.getId(), user.getRole(), ms);

        return new AuthTokenResponse(
                token,
                new AuthTokenResponse.UserSummary(
                        user.getId(),
                        user.getFullName(),
                        user.getPhoneNumber(),
                        user.getEmail(),
                        user.getRole().name()
                ),
                new AuthTokenResponse.BusinessSummary(
                        biz.getId(),
                        biz.getName(),
                        biz.getCustomSubdomain(),
                        biz.getBusinessPhone(),
                        biz.getStreetAddress(),
                        biz.getCity(),
                        biz.getState(),
                        biz.getZipcode(),
                        biz.isOpen24_7(),
                        biz.getBusinessHours().stream()
                                .map(BusinessHoursResponse::from)
                                .toList(),
                        CurrencyPolicy.displayCurrency(biz.getCurrency()),
                        LogoUrls.publicUrl(publicBaseUrl, biz.getLogoKey()),
                        biz.getLocationUrl(),
                        biz.getCountry(),
                        biz.getTimeZone()
                )
        );
    }

    public AuthTokenResponse me(String emailFromJwt) {
        long start = System.currentTimeMillis();
        String email = normalizeEmail(emailFromJwt);

        log.debug("event=auth.me.start email={}", LogMasking.maskEmail(email));

        User user = userRepo.findByEmail(email)
                .orElseThrow(() -> {
                    log.warn("event=auth.me.user_not_found email={}", LogMasking.maskEmail(email));
                    return new NotFoundException("User not found");
                });

        Business biz = businessRepo.findByOwnerId(user.getId())
                .orElseThrow(() -> {
                    log.warn("event=auth.me.business_missing email={} userId={}", LogMasking.maskEmail(email), user.getId());
                    return new ForbiddenException("business not found");
                });

        String token = jwtService.signToken(user.getEmail(), user.getId(), biz.getId(), user.getRole().name());

        long ms = System.currentTimeMillis() - start;
        log.debug("event=auth.me.success email={} userId={} businessId={} duration_ms={}",
                LogMasking.maskEmail(email), user.getId(), biz.getId(), ms);

        return new AuthTokenResponse(
                token,
                new AuthTokenResponse.UserSummary(
                        user.getId(),
                        user.getFullName(),
                        user.getPhoneNumber(),
                        user.getEmail(),
                        user.getRole().name()
                ),
                new AuthTokenResponse.BusinessSummary(
                        biz.getId(),
                        biz.getName(),
                        biz.getCustomSubdomain(),
                        biz.getBusinessPhone(),
                        biz.getStreetAddress(),
                        biz.getCity(),
                        biz.getState(),
                        biz.getZipcode(),
                        biz.isOpen24_7(),
                        biz.getBusinessHours().stream()
                                .map(BusinessHoursResponse::from)
                                .toList(),
                        CurrencyPolicy.displayCurrency(biz.getCurrency()),
                        LogoUrls.publicUrl(publicBaseUrl, biz.getLogoKey()),
                        biz.getLocationUrl(),
                        biz.getCountry(),
                        biz.getTimeZone()
                )
        );
    }

    @Transactional
    public void changePassword(String emailFromJwt, ChangePasswordRequest req) {
        String email = normalizeEmail(emailFromJwt);
        log.info("event=auth.change_password.start email={}", LogMasking.maskEmail(email));

        User user = userRepo.findByEmail(email)
                .orElseThrow(() -> new NotFoundException("User not found"));

        if (StrongPasswordValidator.exceedsBcryptLimit(req.getCurrentPassword())
                || !passwordEncoder.matches(req.getCurrentPassword(), user.getPasswordHash())) {
            log.warn("event=auth.change_password.wrong_current email={} userId={}", LogMasking.maskEmail(email), user.getId());
            throw new BadRequestException("Current password is incorrect");
        }

        user.setPasswordHash(passwordEncoder.encode(req.getNewPassword()));
        userRepo.save(user);

        log.info("event=auth.change_password.success email={} userId={}", LogMasking.maskEmail(email), user.getId());
    }

    private void validateBusinessHours(boolean open24_7, List<BusinessHourDto> hours) {
        if (open24_7) return;

        if (hours == null || hours.size() != 7) {
            log.warn("event=auth.hours.invalid_count open24_7=false hoursCount={}",
                    (hours == null ? 0 : hours.size()));
            throw new BadRequestException("businessHours must contain exactly 7 entries when open24_7 is false");
        }

        Set<DayOfWeekEnum> unique = new HashSet<>();
        int openDays = 0;

        for (BusinessHourDto h : hours) {
            unique.add(h.getDayOfWeek());
            if (h.isOpen()) openDays++;

            if (!h.isOpen()) {
                if (h.getStartTime() != null || h.getEndTime() != null) {
                    log.warn("event=auth.hours.closed_with_times day={} start={} end={}",
                            h.getDayOfWeek(), h.getStartTime(), h.getEndTime());
                    throw new BadRequestException(h.getDayOfWeek() + ": startTime/endTime must be null when closed");
                }
                continue;
            }

            if (h.getStartTime() == null || h.getEndTime() == null) {
                log.warn("event=auth.hours.missing_time day={} start={} end={}",
                        h.getDayOfWeek(), h.getStartTime(), h.getEndTime());
                throw new BadRequestException(h.getDayOfWeek() + ": startTime and endTime are required when open");
            }
            if (!HHMM.matcher(h.getStartTime()).matches() || !HHMM.matcher(h.getEndTime()).matches()) {
                log.warn("event=auth.hours.bad_format day={} start={} end={}",
                        h.getDayOfWeek(), h.getStartTime(), h.getEndTime());
                throw new BadRequestException("Invalid time format (HH:mm) for "+ h.getDayOfWeek());
            }
            if (timeToMinutes(h.getStartTime()) >= timeToMinutes(h.getEndTime())) {
                log.warn("event=auth.hours.bad_range day={} start={} end={}",
                        h.getDayOfWeek(), h.getStartTime(), h.getEndTime());
                throw new BadRequestException(h.getDayOfWeek() + ": startTime must be before endTime");
            }
        }

        if (unique.size() != 7) {
            log.warn("event=auth.hours.duplicate_day uniqueDays={}", unique.size());
            throw new BadRequestException("businessHours must include exactly one entry for each day (MONDAY..SUNDAY)");
        }
        if (openDays == 0) {
            log.warn("event=auth.hours.no_open_days");
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

    // --- helpers ---

    private static String safe(String s) {
        return s == null ? "" : s.replaceAll("[\\r\\n\\t]", " ").trim();
    }
}
