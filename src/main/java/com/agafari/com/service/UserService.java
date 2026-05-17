package com.agafari.com.service;

import com.agafari.com.dto.request.UserUpdateRequest;
import com.agafari.com.dto.response.UserResponse;

public interface UserService {
    UserResponse updateUser(UserUpdateRequest request);
}