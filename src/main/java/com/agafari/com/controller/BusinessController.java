package com.agafari.com.controller;

import com.agafari.com.dto.request.BusinessUpdateRequest;
import com.agafari.com.dto.request.UpdateBusinessHoursRequest;
import com.agafari.com.dto.response.BusinessHoursResponse;
import com.agafari.com.dto.response.BusinessResponse;
import com.agafari.com.service.BusinessService;
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
            @RequestBody BusinessUpdateRequest request
    ) {
        return ResponseEntity.ok(businessService.updateBusiness(businessId, request));
    }
}