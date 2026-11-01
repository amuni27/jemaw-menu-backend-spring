package com.agafari.com.dto.request;

import lombok.Data;

/** AGF-S2-22: the token from a verification link. Blank or malformed tokens get the same generic answer. */
@Data
public class VerifyEmailRequest {
    private String token;
}
