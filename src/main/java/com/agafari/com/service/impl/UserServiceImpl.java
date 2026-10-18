package com.agafari.com.service.impl;

import com.agafari.com.dto.request.UserUpdateRequest;
import com.agafari.com.dto.response.UserResponse;
import com.agafari.com.exception.BadRequestException;
import com.agafari.com.exception.ConflictException;
import com.agafari.com.exception.NotFoundException;
import com.agafari.com.jpa.entity.User;
import com.agafari.com.jpa.repository.UserRepository;
import com.agafari.com.security.CurrentUser;
import com.agafari.com.service.JwtService;
import com.agafari.com.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class UserServiceImpl implements UserService {

    private final CurrentUser currentUser;
    private final UserRepository userRepo;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    @Override
    @Transactional
    public UserResponse updateUser(UserUpdateRequest request) {
        String userId = currentUser.userId();
        log.info("Updating user userId: {}", userId);

        User user = userRepo.findById(userId)
                .orElseThrow(() -> new NotFoundException("User not found"));

        if (request.getFullName() != null)    user.setFullName(request.getFullName().trim());
        if (request.getPhoneNumber() != null) user.setPhoneNumber(request.getPhoneNumber().trim());

        boolean emailChanged = false;
        if (request.getEmail() != null) {
            String newEmail = request.getEmail().trim().toLowerCase();
            if (!newEmail.equals(user.getEmail())) {
                // AGF-S1-07: changing the login email requires the current password
                if (request.getCurrentPassword() == null || request.getCurrentPassword().isBlank()) {
                    throw new BadRequestException("Current password is required to change email");
                }
                if (!passwordEncoder.matches(request.getCurrentPassword(), user.getPasswordHash())) {
                    log.warn("event=user.email_change.wrong_password userId={}", userId);
                    throw new BadRequestException("Current password is incorrect");
                }
                if (userRepo.findByEmail(newEmail).isPresent()) {
                    throw new ConflictException("This email is already registered");
                }
                user.setEmail(newEmail);
                emailChanged = true;
            }
        }

        userRepo.save(user);
        log.info("User updated userId: {} emailChanged={}", userId, emailChanged);

        UserResponse response = UserResponse.from(user);
        if (emailChanged) {
            // The email is the token subject (PO-6), so the old token stops working: issue a new one
            response.setToken(jwtService.signToken(user.getEmail(), user.getId(), currentUser.me().getBusinessId(), user.getRole().name()));
        }
        return response;
    }
}
