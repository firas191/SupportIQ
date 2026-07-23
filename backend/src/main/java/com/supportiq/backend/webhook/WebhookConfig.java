package com.supportiq.backend.webhook;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Active WebhookProperties et branche le rate limiting sur les routes du webhook. */
@Configuration
@EnableConfigurationProperties(WebhookProperties.class)
public class WebhookConfig implements WebMvcConfigurer {

    private final WebhookRateLimitInterceptor rateLimitInterceptor;

    public WebhookConfig(WebhookRateLimitInterceptor rateLimitInterceptor) {
        this.rateLimitInterceptor = rateLimitInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(rateLimitInterceptor).addPathPatterns("/api/webhooks/**");
    }
}
