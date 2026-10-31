package com.agafari.com.controller;

import com.agafari.com.dto.request.CategorySpecialRequest;
import com.agafari.com.dto.response.CategorySpecialResponse;
import com.agafari.com.service.impl.CategorySpecialServiceImpl;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** AGF-S2-36 (checkpoint A): the owner's category specials (own restaurant only). /api/specials is unchanged. */
@RestController
@RequestMapping("/api/category-specials")
@RequiredArgsConstructor
public class CategorySpecialController {

    private final CategorySpecialServiceImpl specials;

    @GetMapping
    public ResponseEntity<List<CategorySpecialResponse>> list() {
        return ResponseEntity.ok(specials.list());
    }

    @PostMapping
    public ResponseEntity<CategorySpecialResponse> create(@RequestBody CategorySpecialRequest request) {
        return ResponseEntity.status(201).body(specials.create(request));
    }

    @PatchMapping("/{specialId}")
    public ResponseEntity<CategorySpecialResponse> update(@PathVariable String specialId, @RequestBody CategorySpecialRequest request) {
        return ResponseEntity.ok(specials.update(specialId, request));
    }

    @DeleteMapping("/{specialId}")
    public ResponseEntity<Void> delete(@PathVariable String specialId) {
        specials.delete(specialId);
        return ResponseEntity.noContent().build();
    }
}
