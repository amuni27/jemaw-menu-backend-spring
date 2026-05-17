package com.agafari.com.controller;

import com.agafari.com.dto.request.UserUpdateRequest;
import com.agafari.com.dto.response.UserResponse;
import com.agafari.com.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/user")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @PatchMapping
    public ResponseEntity<UserResponse> updateUser(@RequestBody UserUpdateRequest request) {
        return ResponseEntity.ok(userService.updateUser(request));
    }
}
