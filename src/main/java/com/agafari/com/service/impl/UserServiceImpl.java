package com.agafari.com.service.impl;

import com.agafari.com.dto.request.UserUpdateRequest;
import com.agafari.com.dto.response.UserResponse;
import com.agafari.com.exception.ConflictException;
import com.agafari.com.exception.NotFoundException;
import com.agafari.com.jpa.entity.User;
import com.agafari.com.jpa.repository.UserRepository;
import com.agafari.com.security.CurrentUser;
import com.agafari.com.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class UserServiceImpl implements UserService {

    private final CurrentUser currentUser;
    private final UserRepository userRepo;

    @Override
    @Transactional
    public UserResponse updateUser(UserUpdateRequest request) {
        String userId = currentUser.userId();
        log.info("Updating user userId: {}", userId);

        User user = userRepo.findById(userId)
                .orElseThrow(() -> new NotFoundException("User not found"));

        if (request.getFullName() != null)    user.setFullName(request.getFullName().trim());
        if (request.getPhoneNumber() != null) user.setPhoneNumber(request.getPhoneNumber().trim());

        if (request.getEmail() != null) {
            String newEmail = request.getEmail().trim().toLowerCase();
            if (!newEmail.equals(user.getEmail())) {
                if (userRepo.findByEmail(newEmail).isPresent()) {
                    throw new ConflictException("This email is already registered");
                }
                user.setEmail(newEmail);
            }
        }

        userRepo.save(user);
        log.info("User updated userId: {}", userId);
        return UserResponse.from(user);
    }
}
