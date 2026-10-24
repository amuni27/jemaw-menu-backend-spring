package com.agafari.com.security;

import lombok.extern.slf4j.Slf4j;

/** Default delivery until an email provider is chosen (D5): nothing is sent, and only a masked event is logged. */
@Slf4j
public class DisabledPasswordResetMailer implements PasswordResetMailer {

    @Override
    public void sendResetLink(String email, String resetUrl) {
        log.warn("event=password_reset.delivery_disabled email={} reason=\"no email provider configured (D5)\"",
                LogMasking.maskEmail(email));
    }
}
