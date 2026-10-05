package org.dbplatform.controlplane.config;

import java.io.IOException;
import java.util.List;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

/** CORS for the Vite dev server and the SPA fallback for the UI build served from {@code classpath:/static/}. */
@Configuration
public class WebConfig implements WebMvcConfigurer {
    private final DbpProperties props;

    public WebConfig(DbpProperties props) { this.props = props; }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        List<String> origins = props.getCors().getAllowedOrigins();
        registry.addMapping("/api/**")
                .allowedOriginPatterns(origins.toArray(String[]::new))
                .allowedMethods("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS")
                .allowedHeaders("*")
                .exposedHeaders("Location")
                .allowCredentials(true)
                .maxAge(3600);
        registry.addMapping("/actuator/**").allowedOriginPatterns(origins.toArray(String[]::new)).allowedMethods("GET");
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/**")
                .addResourceLocations("classpath:/static/")
                .resourceChain(true)
                .addResolver(new PathResourceResolver() {
                    @Override
                    protected Resource getResource(String resourcePath, Resource location) throws IOException {
                        Resource requested = location.createRelative(resourcePath);
                        if (requested.exists() && requested.isReadable()) return requested;
                        // SPA fallback: unknown, extension-less, non-API paths render index.html
                        if (resourcePath.startsWith("api/") || resourcePath.startsWith("actuator/") || resourcePath.startsWith("v3/")
                                || resourcePath.startsWith("swagger-ui") || resourcePath.startsWith("h2-console")) return null;
                        String last = resourcePath.substring(resourcePath.lastIndexOf('/') + 1);
                        if (last.contains(".")) return null;
                        return new ClassPathResource("/static/index.html");
                    }
                });
    }
}
