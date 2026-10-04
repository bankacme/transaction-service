package com.bank.transaction.infrastructure.config;

import com.bank.transaction.infrastructure.adapter.out.persistence.TransactionDocument;
import com.bank.transaction.infrastructure.adapter.out.persistence.TransferDocument;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.index.PartialIndexFilter;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.stereotype.Component;

/**
 * Creates every index from data-model.md sections 3.1/3.2 explicitly at startup — same
 * approach proven in bank-spike and already used by account-service's own {@code
 * AccountIndexInitializer}, rather than relying on {@code @CompoundIndex} + {@code
 * auto-index-creation}. The one partial index here ({@code ix_tx_transfer}) uses only an
 * existence filter, which every MongoDB version supports.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TransactionIndexInitializer implements ApplicationRunner {

    private final ReactiveMongoTemplate mongoTemplate;

    @Override
    public void run(ApplicationArguments args) {
        createTransactionIndexes();
        createTransferIndexes();
    }

    private void createTransactionIndexes() {
        Index operationId = new Index()
                .on("operationId", Sort.Direction.ASC)
                .unique()
                .named("uk_tx_operation_id");

        Index productOccurred = new Index()
                .on("productId", Sort.Direction.ASC)
                .on("occurredAt", Sort.Direction.DESC)
                .named("ix_tx_product_occurred");

        Index customerOccurred = new Index()
                .on("customerId", Sort.Direction.ASC)
                .on("occurredAt", Sort.Direction.DESC)
                .named("ix_tx_customer_occurred");

        Index statusCreated = new Index()
                .on("status", Sort.Direction.ASC)
                .on("createdAt", Sort.Direction.ASC)
                .named("ix_tx_status_created");

        Index transferId = new Index()
                .on("transferId", Sort.Direction.ASC)
                .partial(PartialIndexFilter.of(Criteria.where("transferId").exists(true)))
                .named("ix_tx_transfer");

        createIndex(TransactionDocument.class, operationId);
        createIndex(TransactionDocument.class, productOccurred);
        createIndex(TransactionDocument.class, customerOccurred);
        createIndex(TransactionDocument.class, statusCreated);
        createIndex(TransactionDocument.class, transferId);
    }

    private void createTransferIndexes() {
        Index operationId = new Index()
                .on("operationId", Sort.Direction.ASC)
                .unique()
                .named("uk_transfer_operation_id");

        Index statusUpdated = new Index()
                .on("status", Sort.Direction.ASC)
                .on("updatedAt", Sort.Direction.ASC)
                .named("ix_transfer_status_updated");

        Index sourceCreated = new Index()
                .on("sourceAccountId", Sort.Direction.ASC)
                .on("createdAt", Sort.Direction.DESC)
                .named("ix_transfer_source_created");

        Index targetCreated = new Index()
                .on("targetAccountId", Sort.Direction.ASC)
                .on("createdAt", Sort.Direction.DESC)
                .named("ix_transfer_target_created");

        createIndex(TransferDocument.class, operationId);
        createIndex(TransferDocument.class, statusUpdated);
        createIndex(TransferDocument.class, sourceCreated);
        createIndex(TransferDocument.class, targetCreated);
    }

    private void createIndex(Class<?> documentType, Index index) {
        mongoTemplate.indexOps(documentType)
                .createIndex(index)
                .subscribe(
                        name -> log.info("Index '{}' ready on {}", name, documentType.getSimpleName()),
                        error -> log.error("Could not create index '{}' on {}", index.getIndexKeys(),
                                documentType.getSimpleName(), error));
    }
}
