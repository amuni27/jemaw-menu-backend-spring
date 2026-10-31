package com.agafari.com.controller;

import com.agafari.com.dto.request.SpecialRequest;
import com.agafari.com.dto.response.SpecialResponse;
import com.agafari.com.service.impl.SpecialServiceImpl;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** AGF-S2-25 (checkpoint A): the owner's specials (own restaurant only). Guests get special data in checkpoint B. */
@RestController
@RequestMapping("/api/specials")
@RequiredArgsConstructor
public class SpecialController {

    private final SpecialServiceImpl specials;

    @GetMapping
    public ResponseEntity<List<SpecialResponse>> list() {
        return ResponseEntity.ok(specials.list());
    }

    @PostMapping
    public ResponseEntity<SpecialResponse> create(@RequestBody SpecialRequest request) {
        return ResponseEntity.status(201).body(specials.create(request));
    }

    @PatchMapping("/{specialId}")
    public ResponseEntity<SpecialResponse> update(@PathVariable String specialId, @RequestBody SpecialRequest request) {
        return ResponseEntity.ok(specials.update(specialId, request));
    }

    @DeleteMapping("/{specialId}")
    public ResponseEntity<Void> delete(@PathVariable String specialId) {
        specials.delete(specialId);
        return ResponseEntity.noContent().build();
    }
}
