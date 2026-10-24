package com.agafari.com.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.nio.charset.StandardCharsets;

public class StrongPasswordValidator implements ConstraintValidator<StrongPassword, String> {

    /** BCrypt only uses the first 72 bytes and Spring Security rejects longer input. */
    public static final int MAX_BYTES = 72;

    public static boolean exceedsBcryptLimit(String password) {
        return password != null && password.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES;
    }

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null) return true; // @NotBlank reports missing values
        return value.length() >= 8
                && !exceedsBcryptLimit(value)
                && value.chars().anyMatch(Character::isUpperCase)
                && value.chars().anyMatch(Character::isLowerCase)
                && value.chars().anyMatch(Character::isDigit)
                && value.chars().anyMatch(c -> !Character.isLetterOrDigit(c));
    }
}
