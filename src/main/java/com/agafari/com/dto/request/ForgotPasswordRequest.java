package com.agafari.com.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** AGF-S2-13A */
@Data
public class ForgotPasswordRequest {

    @NotBlank
    @Email
    @Size(max = 254)
    private String email;
}
