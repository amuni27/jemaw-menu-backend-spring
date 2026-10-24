package com.agafari.com.dto.request;

import com.agafari.com.validation.StrongPassword;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.ToString;

/** AGF-S2-13A. Neither value appears in {@code toString}. */
@Data
public class ResetPasswordRequest {

    @NotBlank
    @Size(max = 1024)
    @ToString.Exclude
    private String token;

    @NotBlank
    @StrongPassword // AGF-S1-17
    @ToString.Exclude
    private String newPassword;
}
