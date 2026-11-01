package com.agafari.com.exception;

/** AGF E4-M2: the configuration changed since the admin loaded it (stale {@code expectedVersion} or a concurrent save). */
public class ConfigVersionConflictException extends RuntimeException {

    public static final String CODE = "CONFIG_VERSION_CONFLICT";

    public ConfigVersionConflictException() {
        super("The configuration was changed by someone else. Reload to see the latest version.");
    }
}
