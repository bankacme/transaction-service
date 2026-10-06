package com.bank.transaction.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.web.reactive.function.client.WebClientCustomizer;
import org.springframework.web.reactive.function.client.WebClient;

class LoadBalancerConfigTest {

    private final AtomicInteger customized = new AtomicInteger();
    private final DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
    private final LoadBalancerConfig config = new LoadBalancerConfig();

    LoadBalancerConfigTest() {
        WebClientCustomizer customizer = builder -> customized.incrementAndGet();
        beanFactory.registerSingleton("customizer", customizer);
    }

    @Test
    void bothBuildersApplySpringBootCustomizers() {
        WebClient.Builder plain = config.webClientBuilder(beanFactory.getBeanProvider(WebClientCustomizer.class));
        WebClient.Builder loadBalanced = config.loadBalancedWebClientBuilder(
                beanFactory.getBeanProvider(WebClientCustomizer.class));

        assertThat(plain).isNotSameAs(loadBalanced);
        assertThat(customized).hasValue(2);
    }

    @Test
    void thePlainBuilderHasNoLoadBalancerFilter() {
        WebClient.Builder plain = config.webClientBuilder(beanFactory.getBeanProvider(WebClientCustomizer.class));

        plain.filters(filters -> assertThat(filters).isEmpty());
    }
}
