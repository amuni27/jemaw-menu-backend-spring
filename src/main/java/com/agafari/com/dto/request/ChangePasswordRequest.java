package com.agafari.com.dto.request;

import jakarta.validation.constraints.NotBlank;
import com.agafari.com.validation.StrongPassword;
import lombok.Data;

@Data
public class ChangePasswordRequest {

    @NotBlank
    private String currentPassword;

    @NotBlank
    @StrongPassword // AGF-S1-17
    private String newPassword;
}