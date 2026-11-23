package com.agafari.com.billing;

import com.agafari.com.service.MonetizationClock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;

/**
 * AGF B-L1 (register §93): reads the local sandbox exception's inputs once. Off unless explicitly opted in on the command
 * line; opted in with any unmet condition, the application refuses to start. Secrets are only classified, never kept.
 */
@Configuration(proxyBeanMethods = false)
public class LocalSandboxConfiguration {

    @Bean
    LocalSandboxActivation localSandboxActivation(ConfigurableEnvironment environment, MonetizationClock clock) {
        PropertySource<?> os = environment.getPropertySources().get(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        return new LocalSandboxActivation(LocalSandboxActivation.Inputs.from(environment, name -> {
            Object value = os == null ? null : os.getProperty(name);
            return value == null ? null : value.toString();
        }), clock);
    }
}
