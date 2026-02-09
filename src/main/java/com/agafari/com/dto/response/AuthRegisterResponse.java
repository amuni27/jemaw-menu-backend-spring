package com.agafari.com.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class AuthRegisterResponse {
    private String message;
    private String token;
}
