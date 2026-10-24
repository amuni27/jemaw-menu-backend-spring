package com.agafari.com.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Password policy for new passwords (AGF-S1-17): 8-72 characters with upper, lower, number and symbol. */
@Documented
@Constraint(validatedBy = StrongPasswordValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
public @interface StrongPassword {
    String message() default "Password must be 8-72 characters and include an uppercase letter, a lowercase letter, a number and a symbol";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
