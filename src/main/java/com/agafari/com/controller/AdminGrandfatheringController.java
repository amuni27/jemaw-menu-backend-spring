package com.agafari.com.controller;

import com.agafari.com.dto.response.GrandfatheringPreview;
import com.agafari.com.enums.MonetizationModel;
import com.agafari.com.enums.MonetizationSetting;
import com.agafari.com.service.GrandfatheringService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** AGF E4-M6: read-only preview of what a configuration would grandfather ({@code /api/admin/**}: database-backed ADMIN role). */
@RestController
@RequestMapping("/api/admin/grandfathering")
@RequiredArgsConstructor
public class AdminGrandfatheringController {

    private final GrandfatheringService grandfathering;

    @GetMapping("/preview")
    public ResponseEntity<GrandfatheringPreview> preview(@RequestParam MonetizationSetting setting,
                                                         @RequestParam(required = false) List<MonetizationModel> models) {
        return ResponseEntity.ok(grandfathering.preview(setting, models == null ? List.of() : models));
    }
}
