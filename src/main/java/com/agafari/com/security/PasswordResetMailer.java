package com.agafari.com.security;

/**
 * Delivers password-reset links (AGF-S2-13A). The email provider is decided in D5; until then the application's only
 * implementation is {@link DisabledPasswordResetMailer}, which sends nothing. Implementations must never log the link.
 */
public interface PasswordResetMailer {

    void sendResetLink(String email, String resetUrl);
}
