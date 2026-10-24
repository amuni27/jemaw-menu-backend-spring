package com.agafari.com.dto.request;

import jakarta.validation.constraints.Email;
import lombok.Data;

@Data
public class UserUpdateRequest {
    private String fullName;
    @Email
    private String email;
    private String phoneNumber;

    /** Required when changing the email (AGF-S1-07). */
    private String currentPassword;
}
