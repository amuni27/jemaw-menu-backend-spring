package com.agafari.com.controller;

import com.agafari.com.dto.request.BusinessUpdateRequest;
import com.agafari.com.dto.request.UpdateBusinessHoursRequest;
import com.agafari.com.dto.response.BusinessHoursResponse;
import com.agafari.com.dto.response.BusinessResponse;
import com.agafari.com.dto.response.MenuViewsResponse;
import com.agafari.com.service.BusinessService;
import com.agafari.com.service.impl.MenuViewServiceImpl;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/business")
@RequiredArgsConstructor
public class BusinessController {

    private final BusinessService businessService;
    private final MenuViewServiceImpl menuViews; // AGF-S2-24

    @GetMapping("/{businessId}/hours")
    public ResponseEntity<List<BusinessHoursResponse>> getBusinessHours(@PathVariable String businessId) {
        return ResponseEntity.ok(businessService.getBusinessHours(businessId));
    }

    @PatchMapping("/{businessId}/hours")
    public ResponseEntity<List<BusinessHoursResponse>> updateBusinessHours(
            @PathVariable String businessId,
            @Valid @RequestBody UpdateBusinessHoursRequest request
    ) {
        return ResponseEntity.ok(businessService.updateBusinessHours(businessId, request));
    }

    @PatchMapping("/{businessId}")
    public ResponseEntity<BusinessResponse> updateBusiness(
            @PathVariable String businessId,
            @Valid @RequestBody BusinessUpdateRequest request
    ) {
        return ResponseEntity.ok(businessService.updateBusiness(businessId, request));
    }

    /** AGF-S2-24: daily guest menu openings of the owner's restaurant (1–90 days, default 30). */
    @GetMapping("/{businessId}/menu-views")
    public ResponseEntity<MenuViewsResponse> getMenuViews(
            @PathVariable String businessId,
            @RequestParam(defaultValue = "" + MenuViewServiceImpl.DEFAULT_DAYS) int days
    ) {
        return ResponseEntity.ok(menuViews.views(businessId, days));
    }
}
