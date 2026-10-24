package com.agafari.com.config;

import com.agafari.com.service.JwtService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AGF-S1-01: secret-consuming beans start only when every secret is supplied,
 * and a missing or blank secret fails startup without leaking any value.
 * Uses the real application.yml; no database or network is needed.
 */
class SecretsStartupTest {

    private static final Map<String, String> ALL_SECRETS = Map.of(
            "APP_JWT_SECRET", "dummy-jwt-secret-value-0123456789-abcdefghij",
            "CLOUDFLARE_R2_ACCESS_KEY", "dummy-access-key-value",
            "CLOUDFLARE_R2_SECRET_KEY", "dummy-secret-key-value",
            "CLOUDFLARE_R2_ENDPOINT", "https://r2-endpoint.example.invalid",
            "CLOUDFLARE_R2_BUCKET", "dummy-bucket-value",
            "CLOUDFLARE_R2_PUBLIC_BASE_URL", "https://public-host.example.invalid"
    );

    @Test
    void startsWhenAllSecretsAreSupplied() {
        runner(ALL_SECRETS).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(S3Client.class);
            assertThat(context).hasSingleBean(S3Presigner.class);

            JwtService jwt = context.getBean(JwtService.class);
            String token = jwt.signToken("owner@example.com", "user-1", "biz-1", "VENUE");
            assertThat(jwt.extractSubject(token)).isEqualTo("owner@example.com");
            assertThat(jwt.extractBusinessId(token)).isEqualTo("biz-1");
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "APP_JWT_SECRET",
            "CLOUDFLARE_R2_ACCESS_KEY",
            "CLOUDFLARE_R2_SECRET_KEY",
            "CLOUDFLARE_R2_ENDPOINT",
            "CLOUDFLARE_R2_BUCKET",
            "CLOUDFLARE_R2_PUBLIC_BASE_URL"
    })
    void failsFastNamingTheMissingSecret(String missing) {
        Map<String, String> secrets = new LinkedHashMap<>(ALL_SECRETS);
        secrets.remove(missing);

        runner(secrets).run(context -> {
            assertThat(context).hasFailed();
            String message = fullMessage(context.getStartupFailure());
            assertThat(message).contains("Could not resolve placeholder '" + missing + "'");
            assertNoSecretValueIn(message);
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "CLOUDFLARE_R2_ACCESS_KEY",
            "CLOUDFLARE_R2_SECRET_KEY",
            "CLOUDFLARE_R2_ENDPOINT",
            "CLOUDFLARE_R2_BUCKET",
            "CLOUDFLARE_R2_PUBLIC_BASE_URL"
    })
    void failsFastWhenAnR2SecretIsBlank(String blank) {
        Map<String, String> secrets = new LinkedHashMap<>(ALL_SECRETS);
        secrets.put(blank, " ");

        runner(secrets).run(context -> {
            assertThat(context).hasFailed();
            String message = fullMessage(context.getStartupFailure());
            assertThat(message).contains("must not be blank; set the " + blank + " environment variable");
            assertNoSecretValueIn(message);
        });
    }

    @Test
    void failsFastWhenJwtSecretIsTooShort() {
        Map<String, String> secrets = new LinkedHashMap<>(ALL_SECRETS);
        secrets.put("APP_JWT_SECRET", "too-short");

        runner(secrets).run(context -> {
            assertThat(context).hasFailed();
            String message = fullMessage(context.getStartupFailure());
            assertThat(message).contains("app.jwt.secret must be at least 32 characters");
            assertThat(message).doesNotContain("too-short");
        });
    }

    private static ApplicationContextRunner runner(Map<String, String> environment) {
        return new ApplicationContextRunner()
                .withInitializer(context -> {
                    try {
                        for (PropertySource<?> source : new YamlPropertySourceLoader()
                                .load("application.yml", new ClassPathResource("application.yml"))) {
                            context.getEnvironment().getPropertySources().addLast(source);
                        }
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                })
                .withPropertyValues(environment.entrySet().stream()
                        .map(e -> e.getKey() + "=" + e.getValue())
                        .toArray(String[]::new))
                .withUserConfiguration(StrictPlaceholders.class, CloudflareR2Config.class, JwtService.class);
    }

    private static void assertNoSecretValueIn(String message) {
        ALL_SECRETS.values().forEach(value -> assertThat(message).doesNotContain(value));
    }

    private static String fullMessage(Throwable failure) {
        StringBuilder sb = new StringBuilder();
        for (Throwable t = failure; t != null; t = t.getCause()) {
            sb.append(t).append('\n');
        }
        return sb.toString();
    }

    /** Same strict placeholder handling Spring Boot applies at runtime. */
    @Configuration(proxyBeanMethods = false)
    static class StrictPlaceholders {
        @Bean
        static PropertySourcesPlaceholderConfigurer propertySourcesPlaceholderConfigurer() {
            return new PropertySourcesPlaceholderConfigurer();
        }
    }
}
