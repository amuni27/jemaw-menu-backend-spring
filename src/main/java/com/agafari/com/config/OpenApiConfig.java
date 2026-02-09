package com.agafari.com.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI api() {
        final String schemeName = "bearerAuth";

        return new OpenAPI()
                .info(new Info()
                        .title("AfroDebab Content System API")
                        .version("1.0.0")
                        .description("Blogs, Events, Jobs, Job Applications. Public + Admin (JWT)."))
                .components(new Components()
                        .addSecuritySchemes(schemeName,
                                new SecurityScheme()
                                        .name(schemeName)
                                        .type(SecurityScheme.Type.HTTP)
                                        .scheme("bearer")
                                        .bearerFormat("JWT")))
                // This makes swagger send the token when you click "Authorize"
                .addSecurityItem(new SecurityRequirement().addList(schemeName));
    }
}
