package org.dbplatform.controlplane.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {
    @Bean
    public OpenAPI openApi() {
        return new OpenAPI()
                .info(new Info().title("Database Access Platform — control plane API").version("v1")
                        .description("Configuration, identity, routing, credentials, metadata catalogue, ownership graph, impact analysis, telemetry and governance. "
                                + "The hand-written contract in docs/control-plane-api.md is authoritative where the two differ.")
                        .license(new License().name("Apache-2.0")))
                .components(new Components()
                        .addSecuritySchemes("serviceToken", new SecurityScheme().type(SecurityScheme.Type.APIKEY).in(SecurityScheme.In.HEADER).name("X-DBP-Service-Token")
                                .description("Shared service token for /api/v1/internal/** (DBP_SERVICE_TOKEN)"))
                        .addSecuritySchemes("basic", new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("basic")
                                .description("Operator credentials when DBP_SECURITY_MODE=basic")));
    }
}
