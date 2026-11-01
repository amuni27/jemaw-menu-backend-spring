package com.agafari.com.security;

/**
 * Delivers email-verification links (AGF-S2-22). The email provider is decided in D5; until then the application's only
 * implementation is {@link DisabledEmailVerificationMailer}, which sends nothing and is not operational. Implementations
 * must never log the link, and throw when delivery fails so the owner gets an honest answer.
 */
public interface EmailVerificationMailer {

    void sendVerificationLink(String email, String verifyUrl);

    /** False for a mailer that can't deliver (the default before D5); verification is then unavailable. */
    default boolean operational() {
        return true;
    }
}
