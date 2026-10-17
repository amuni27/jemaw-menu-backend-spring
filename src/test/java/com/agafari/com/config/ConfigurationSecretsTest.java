package com.agafari.com.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AGF-S1-01: tracked configuration must not contain credentials.
 * Every secret must be an environment placeholder with no default value.
 */
class ConfigurationSecretsTest {

    private static final Pattern HEX_RUN = Pattern.compile("[0-9a-fA-F]{32,}");
    private static final Pattern KEY_LIKE = Pattern.compile("^[A-Za-z0-9+/=_\\-]{32,}$");

    @Test
    void baseConfigReadsEverySecretFromEnvironmentWithoutDefaults() throws IOException {
        Map<String, String> base = load("application.yml");

        assertThat(base)
                .containsEntry("spring.datasource.password", "${DB_PASSWORD}")
                .containsEntry("app.jwt.secret", "${APP_JWT_SECRET}")
                .containsEntry("cloudflare.accessKey", "${CLOUDFLARE_R2_ACCESS_KEY}")
                .containsEntry("cloudflare.secretKey", "${CLOUDFLARE_R2_SECRET_KEY}")
                .containsEntry("cloudflare.endpoint", "${CLOUDFLARE_R2_ENDPOINT}")
                .containsEntry("cloudflare.bucket", "${CLOUDFLARE_R2_BUCKET}")
                .containsEntry("cloudflare.publicBaseUrl", "${CLOUDFLARE_R2_PUBLIC_BASE_URL}");
    }

    @Test
    void prodConfigReadsSecretsFromEnvironmentAndDoesNotOverrideCloudflare() throws IOException {
        Map<String, String> prod = load("application-prod.yml");

        assertThat(prod)
                .containsEntry("spring.datasource.password", "${DB_PASSWORD}")
                .containsEntry("spring.datasource.username", "${DB_USERNAME}")
                .containsEntry("app.jwt.secret", "${APP_JWT_SECRET}");
        assertThat(prod.keySet()).noneMatch(key -> key.startsWith("cloudflare."));
    }

    @Test
    void noTrackedValueLooksLikeACredential() throws IOException {
        for (String file : new String[]{"application.yml", "application-prod.yml"}) {
            load(file).forEach((key, value) -> {
                assertThat(HEX_RUN.matcher(value).find())
                        .as("%s in %s contains a long hex string", key, file)
                        .isFalse();
                assertThat(KEY_LIKE.matcher(value).matches())
                        .as("%s in %s looks like a literal key", key, file)
                        .isFalse();
            });
        }
    }

    @Test
    void localOverrideFileIsOptional() throws IOException {
        assertThat(load("application.yml").get("spring.config.import"))
                .isEqualTo("optional:file:./application-local.yml");
    }

    private static Map<String, String> load(String name) throws IOException {
        Map<String, String> values = new LinkedHashMap<>();
        for (PropertySource<?> source : new YamlPropertySourceLoader().load(name, new ClassPathResource(name))) {
            if (source instanceof EnumerablePropertySource<?> enumerable) {
                for (String property : enumerable.getPropertyNames()) {
                    values.put(property, String.valueOf(enumerable.getProperty(property)));
                }
            }
        }
        return values;
    }
}
