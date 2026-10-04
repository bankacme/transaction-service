package com.bank.transaction.infrastructure.config;

import java.time.Clock;
import java.time.ZoneId;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * One injected {@link Clock}, fixed to {@code bank.zone} (proposed {@code America/Lima}),
 * same pattern proven in account-service and customer-service — every use case that needs
 * "now" ({@code AbstractRegisterMovementUseCase}, {@code StartTransferUseCaseImpl}, {@code
 * RecoverPendingOperationsUseCaseImpl}, all already built in R3 against a {@code Clock}
 * constructor parameter) reads it through this bean instead of {@code Instant.now()}/{@code
 * LocalDate.now()}, so tests can swap in a fixed clock — exactly what the R3 use-case test
 * suite's {@code Clock.fixed(...)} already relies on.
 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock(@Value("${bank.zone}") String zone) {
        return Clock.system(ZoneId.of(zone));
    }
}
