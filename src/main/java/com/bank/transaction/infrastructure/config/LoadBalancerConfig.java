package com.bank.transaction.infrastructure.config;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.web.reactive.function.client.WebClientCustomizer;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Scope;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * P2 (2.3): las llamadas a otros servicios se resuelven por nombre en Eureka
 * ({@code base-url: http://customer-service/api/v1}).
 *
 * <p>Dos builders, para que el balanceo afecte solo a los clientes REST del servicio:
 * <ul>
 *   <li>{@code webClientBuilder} ({@code @Primary}): el mismo que crea Spring Boot. Lo usa el propio
 *       cliente de Eureka para hablar con {@code localhost:8761}; si llevara el balanceo, buscaría un
 *       servicio llamado "localhost" en Eureka (y Eureka se necesitaría a sí mismo para arrancar).</li>
 *   <li>{@code loadBalancedWebClientBuilder} ({@code @LoadBalanced}): Spring Cloud le añade el filtro
 *       que cambia el nombre del servicio por una instancia viva. Los clientes REST lo piden con
 *       {@code @LoadBalanced} en su constructor.</li>
 * </ul>
 * Las pruebas de los clientes arman su propio {@code WebClient.builder()} contra WireMock.
 */
@Configuration
public class LoadBalancerConfig {

    @Bean
    @Primary
    @Scope("prototype")
    public WebClient.Builder webClientBuilder(ObjectProvider<WebClientCustomizer> customizers) {
        return customized(customizers);
    }

    @Bean
    @LoadBalanced
    @Scope("prototype")
    public WebClient.Builder loadBalancedWebClientBuilder(ObjectProvider<WebClientCustomizer> customizers) {
        return customized(customizers);
    }

    /** Lo mismo que hace WebClientAutoConfiguration de Spring Boot (códecs, etc.). */
    private static WebClient.Builder customized(ObjectProvider<WebClientCustomizer> customizers) {
        WebClient.Builder builder = WebClient.builder();
        customizers.orderedStream().forEach(customizer -> customizer.customize(builder));
        return builder;
    }
}
