package com.bank.transaction.infrastructure.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.HandlerTypePredicate;
import org.springframework.web.reactive.config.PathMatchConfigurer;
import org.springframework.web.reactive.config.WebFluxConfigurer;

/**
 * Adds the {@code /api/v1} prefix the contract puts on every path, exactly like
 * account-service's own {@code WebConfig} — copied verbatim, same reasoning: a
 * {@code @WebFluxTest} slice auto-detects any {@code WebFluxConfigurer} {@code @Configuration}
 * in the same package tree without an explicit {@code @Import}, which is why none of the R5
 * controller tests needed to import this class to see {@code /api/v1/...} paths resolve —
 * they only truly exercise that prefix once this class exists (R6).
 */
@Configuration
public class WebConfig implements WebFluxConfigurer {

    private static final String API_PREFIX = "/api/v1";

    @Override
    public void configurePathMatching(PathMatchConfigurer configurer) {
        configurer.addPathPrefix(API_PREFIX, HandlerTypePredicate.forAnnotation(RestController.class));
    }
}
