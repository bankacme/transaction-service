package com.bank.transaction.infrastructure.config;

import java.math.BigDecimal;
import java.util.List;
import org.bson.types.Decimal128;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.data.convert.ReadingConverter;
import org.springframework.data.convert.WritingConverter;
import org.springframework.data.mongodb.ReactiveMongoDatabaseFactory;
import org.springframework.data.mongodb.ReactiveMongoTransactionManager;
import org.springframework.data.mongodb.core.convert.MongoCustomConversions;
import org.springframework.transaction.reactive.TransactionalOperator;

/**
 * Mirrors account-service's own {@code MongoConfig}, trimmed to what transaction-service
 * actually persists: {@code TransactionDocument}/{@code TransferDocument} use {@link
 * BigDecimal} for {@code amount}/{@code resultingBalance}, and left to its defaults Spring
 * Data Mongo stores that as a plain String rather than {@link Decimal128} (the driver's real
 * decimal type) — data-model.md section 2 calls out the same requirement account-service's
 * ficha did. No {@code LocalDate}/{@code YearMonth} converters here, unlike account-service:
 * every persisted field in this service's two documents is an {@code Instant} (data-model.md
 * 3.1/3.2) — the one place {@code LocalDate} appears in this codebase is the in-memory daily
 * limit computation against {@code AccountMovementPort}, which is never written to Mongo.
 *
 * <p>{@link ReactiveMongoTransactionManager} is not created by Boot autoconfiguration for the
 * reactive stack, so it (and the {@link TransactionalOperator} built from it) is declared here
 * explicitly — {@link com.bank.transaction.infrastructure.adapter.out.persistence
 * .MongoUnitOfWorkAdapter} is what actually uses it, for the saga-leg and movement+fee
 * two-collection writes (data-model.md 2.4).
 */
@Configuration
public class MongoConfig {

    @Bean
    public ReactiveMongoTransactionManager reactiveTransactionManager(ReactiveMongoDatabaseFactory factory) {
        return new ReactiveMongoTransactionManager(factory);
    }

    @Bean
    public TransactionalOperator transactionalOperator(ReactiveMongoTransactionManager txManager) {
        return TransactionalOperator.create(txManager);
    }

    @Bean
    public MongoCustomConversions mongoCustomConversions() {
        return new MongoCustomConversions(List.of(
                BigDecimalToDecimal128Converter.INSTANCE,
                Decimal128ToBigDecimalConverter.INSTANCE));
    }

    @WritingConverter
    enum BigDecimalToDecimal128Converter implements Converter<BigDecimal, Decimal128> {
        INSTANCE;

        @Override
        public Decimal128 convert(BigDecimal source) {
            return new Decimal128(source);
        }
    }

    @ReadingConverter
    enum Decimal128ToBigDecimalConverter implements Converter<Decimal128, BigDecimal> {
        INSTANCE;

        @Override
        public BigDecimal convert(Decimal128 source) {
            return source.bigDecimalValue();
        }
    }
}
