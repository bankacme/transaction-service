# Secuencia — Recuperación de operaciones pendientes

Retoma lo que quedó a medias por una caída o un timeout (regla 14): depósitos/retiros `PENDING` y
transferencias detenidas en `STARTED`, `SOURCE_DEBITED` o `COMPENSATING`. Implementado en
`RecoverPendingOperationsUseCaseImpl` (R3/R4). Se dispara cada minuto desde
`TransactionRecoveryScheduler` (`transaction.recovery.cron`, R6) o a mano con
`POST /transaction-recovery-runs` (`InternalController`, interno). Solo toma lo que lleva más de
`transaction.recovery.pending-minutes` (2 por defecto) sin avanzar.

```mermaid
sequenceDiagram
    autonumber
    participant Trg as TransactionRecoveryScheduler / POST /transaction-recovery-runs
    participant UC as RecoverPendingOperationsUseCaseImpl
    participant TxRepo as TransactionRepositoryPort
    participant TRepo as TransferRepositoryPort
    participant AM as AccountMovementPort
    participant Acc as account-service
    participant UOW as UnitOfWorkPort
    participant Evt as TransactionEventPublisherPort

    Trg->>UC: execute(olderThanMinutes o null → valor configurado)
    Note over UC: threshold = ahora - olderThanMinutes

    Note over UC,Evt: 1) Movimientos simples
    UC->>TxRepo: findPendingOlderThan(threshold)
    TxRepo-->>UC: PENDING (se filtran DEPOSIT/WITHDRAWAL)
    loop Por cada movimiento (aislado: un error no corta el resto)
        UC->>AM: apply(misma operationId, cuenta, tipo, monto)
        AM->>Acc: POST /accounts/{id}/movements
        Note over Acc: si ya lo había aplicado, devuelve el resultado original
        alt Aplicado
            UC->>TxRepo: save(complete) + publish(TransactionRegistered)
        else Rechazado
            UC->>TxRepo: save(fail) + publish(TransactionFailed)
        else Error técnico
            Note over UC: sigue PENDING, se reintenta en la próxima corrida
        end
    end

    Note over UC,Evt: 2) Transferencias en curso
    UC->>TRepo: findInProgressOlderThan(threshold)
    TRepo-->>UC: Transfers STARTED / SOURCE_DEBITED / COMPENSATING
    loop Por cada transferencia (aislada)
        alt STARTED — falta el retiro
            UC->>TxRepo: findByOperationId({op}-OUT) (o la crea PENDING si faltara)
            alt OUT ya COMPLETED
                UC->>UOW: saveTransferAndTransaction(SOURCE_DEBITED, OUT)
            else OUT PENDING
                UC->>AM: apply({op}-OUT, origen, TRANSFER_OUT)
                AM->>Acc: POST /accounts/{origen}/movements
                alt Aplicado
                    UC->>UOW: saveTransferAndTransaction(SOURCE_DEBITED, OUT COMPLETED)
                else Rechazado
                    UC->>UOW: saveTransferAndTransaction(FAILED, OUT FAILED)
                    UC->>Evt: publish(TransferFailed)
                end
            end
        else SOURCE_DEBITED — falta el depósito
            UC->>TxRepo: findByOperationId({op}-IN)
            UC->>AM: apply({op}-IN, destino, TRANSFER_IN)
            AM->>Acc: POST /accounts/{destino}/movements
            alt Aplicado
                UC->>UOW: saveTransferAndTransaction(COMPLETED, IN COMPLETED)
                UC->>Evt: publish(TransferCompleted)
            else Rechazado
                UC->>UOW: saveTransferAndTransaction(COMPENSATING, IN FAILED)
                Note over UC: la reversa se intenta en la próxima corrida
            end
        else COMPENSATING — falta devolver el retiro
            UC->>TxRepo: findByOperationId({op}-OUT)
            UC->>AM: reverse({op}-OUT, origen)
            AM->>Acc: POST /accounts/{origen}/movements/{op}-OUT/reversal
            alt Reversa aplicada
                UC->>UOW: saveTransferAndTransaction(COMPENSATED, OUT REVERSED)
                UC->>Evt: publish(TransferFailed)
            else Rechazada y attempts + 1 < max
                UC->>UOW: saveTransferAndTransaction(COMPENSATING, attempts + 1)
            else Rechazada y attempts + 1 ≥ max (5)
                UC->>UOW: saveTransferAndTransaction(COMPENSATION_FAILED)
                UC->>Evt: publish(TransferFailed)
                Note over UC: revisión manual
            end
        end
    end

    UC-->>Trg: RecoveryResult (encontrados, resueltos, siguen en curso)
    Note over Trg: el scheduler lo registra en el log, el endpoint lo devuelve con 200
```

## Notas

- **Por qué es seguro reintentar:** cada paso usa la misma `operationId` que el intento original, y
  `account-service` guarda cada operación en `account_operations`; una repetición devuelve el
  resultado original sin mover el saldo otra vez.
- **Las patas de transferencia no se reintentan como movimientos sueltos.** El paso 1 filtra solo
  `DEPOSIT`/`WITHDRAWAL`; las `TRANSFER_OUT`/`TRANSFER_IN` se retoman a través de su `Transfer`, que
  sabe en qué paso de la saga quedó.
- **Aislamiento por elemento:** un error en un movimiento o una transferencia no corta la corrida
  (`onErrorResumeNext` por elemento); queda para la siguiente.
- **La reversa usa la `operationId` original (`{op}-OUT`) en la ruta**, igual que en
  `transfer-compensation.md`; `{op}-OUT-REV` queda solo en la anotación local.
- **Pendientes conocidos:**
  - En los contadores, un movimiento que falla por error técnico se cuenta como "fallido" aunque
    siga `PENDING`.
  - (Corregido en P2, paso 2.5) Una `Transfer` `SOURCE_DEBITED` sin la pata `{op}-IN` guardada:
    la recuperación la crea (`createMissingCreditPending`), igual que hace con `{op}-OUT` en
    `STARTED`, y sigue con el abono.
