package com.bank.transaction;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * {@code @EnableScheduling} added in R6 — required for {@link
 * com.bank.transaction.infrastructure.scheduler.TransactionRecoveryScheduler}'s
 * {@code @Scheduled} sweep to actually run; the first service in this project ecosystem that
 * needs it (neither {@code account-service} nor {@code customer-service} has a scheduled job).
 */
@SpringBootApplication
@EnableScheduling
public class TransactionServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(TransactionServiceApplication.class, args);
    }
}
