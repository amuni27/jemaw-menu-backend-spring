package com.agafari.com.controller;


import com.agafari.com.dto.request.ConfirmImageUploadRequest;
import com.agafari.com.dto.request.PresignLogoRequest;
import com.agafari.com.dto.response.PresignLogoResponse;
import com.agafari.com.dto.response.BusinessResponse;
import com.agafari.com.service.BusinessLogoService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/business")
@RequiredArgsConstructor
public class BusinessLogoController {

    private final BusinessLogoService businessLogoService;

    @PostMapping("/{businessId}/logo/presign")
    public ResponseEntity<PresignLogoResponse> presignLogo(
            @PathVariable String businessId,
            @Valid @RequestBody PresignLogoRequest req
    ) {
        return ResponseEntity.ok(businessLogoService.presignLogo(businessId, req));
    }

    @PostMapping("/{businessId}/logo/confirm")
    public ResponseEntity<BusinessResponse> confirmLogo(
            @PathVariable String businessId,
            @Valid @RequestBody ConfirmImageUploadRequest req
    ) {
        return ResponseEntity.ok(businessLogoService.confirmLogo(businessId, req));
    }
}
