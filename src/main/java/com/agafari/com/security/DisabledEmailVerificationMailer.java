package com.agafari.com.security;

import lombok.extern.slf4j.Slf4j;

/** Default delivery until an email provider is chosen (D5): nothing is sent, only a masked event is logged. */
@Slf4j
public class DisabledEmailVerificationMailer implements EmailVerificationMailer {

    @Override
    public void sendVerificationLink(String email, String verifyUrl) {
        log.warn("event=email_verification.delivery_disabled email={} reason=\"no email provider configured (D5)\"",
                LogMasking.maskEmail(email));
    }

    @Override
    public boolean operational() {
        return false;
    }
}
