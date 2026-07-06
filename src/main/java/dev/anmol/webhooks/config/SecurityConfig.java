package dev.anmol.webhooks.config;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers {@link AdminApiKeyFilter} for the /admin/* path only. The webhook
 * ingestion endpoints and the actuator health probe stay open, since payment
 * providers call the webhook URLs unauthenticated and only the signature check
 * is trusted there.
 */
@Configuration
public class SecurityConfig {

    @Bean
    public FilterRegistrationBean<AdminApiKeyFilter> adminApiKeyFilter(
            org.springframework.core.env.Environment env) {
        String apiKey = env.getProperty("webhooks.admin.api-key", "");
        FilterRegistrationBean<AdminApiKeyFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new AdminApiKeyFilter(apiKey));
        registration.addUrlPatterns("/admin/*");
        registration.setName("adminApiKeyFilter");
        registration.setOrder(1);
        return registration;
    }
}
