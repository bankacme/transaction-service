# Secuencia — Transferencia con compensación (`POST /transfers`)

Mismo inicio que `transfer-success.md` hasta `SOURCE_DEBITED`. Aquí el depósito en el destino es
rechazado y la saga **revierte** el retiro del origen (regla 8). Implementado en
`StartTransferUseCaseImpl.onCreditRejected` / `reverseDebit` (R3) y en
`AccountMovementClient.reverse` (R7).

```mermaid
sequenceDiagram
    autonumber
    actor C as Cliente HTTP
    participant Ctrl as TransfersController
    participant UC as StartTransferUseCaseImpl
    participant Dom as Transfer / Transaction
    participant UOW as UnitOfWorkPort
    participant AM as AccountMovementPort
    participant Acc as account-service
    participant Evt as TransactionEventPublisherPort
    participant EH as GlobalExceptionHandler

    Note over UC,Acc: Transfer en SOURCE_DEBITED, OUT COMPLETED, IN PENDING
    UC->>AM: apply({op}-IN, destino, TRANSFER_IN, amount)
    AM->>Acc: POST /accounts/{destino}/movements (DEPOSIT)
    Acc-->>AM: 422 (p. ej. NOT_ALLOWED_DAY, MONTHLY_LIMIT_EXCEEDED, ACCOUNT_INACTIVE)
    AM-->>UC: MovementOutcome rechazado

    UC->>Dom: IN.fail(motivo) + transfer.startCompensation(motivo)
    UC->>UOW: saveTransferAndTransaction(COMPENSATING, IN FAILED)

    Note over UC,Acc: Paso 3 — reversa del retiro
    UC->>AM: reverse({op}-OUT, origen)
    AM->>Acc: POST /accounts/{origen}/movements/{op}-OUT/reversal
    Note over AM,Acc: la ruta lleva la operationId ORIGINAL<br/>({op}-OUT-REV solo se usa en la anotación local)
    alt Reversa aplicada
        Acc-->>AM: 200 (saldo, comisión y contador devueltos)
        AM-->>UC: MovementOutcome aplicado
        UC->>Dom: OUT.markReversed(REVERSED) + transfer.compensated()
        UC->>UOW: saveTransferAndTransaction(COMPENSATED, OUT REVERSED)
        UC->>Evt: publish(TransferFailed)
        UC-->>Ctrl: Transfer COMPENSATED
        Ctrl-->>EH: TransferRejectedException
        EH-->>C: 422 TransferRejected (code = motivo del destino, transferId, transferStatus)
    else Reversa rechazada
        Acc-->>AM: 404/422
        AM-->>UC: MovementOutcome rechazado
        UC->>Dom: OUT.markReversalFailed(REVERSAL_FAILED, code) + transfer.compensationAttemptFailed()
        UC->>UOW: saveTransferAndTransaction(COMPENSATING, attempts + 1)
        Note over UC: sin evento: sigue en curso
        UC-->>Ctrl: Transfer COMPENSATING
        Ctrl-->>C: 202 Accepted (la Transfer en curso)
        Note over UC,Acc: el job de recuperación reintenta la reversa<br/>hasta transfer.compensation.max-attempts (5)<br/>y entonces pasa a COMPENSATION_FAILED
    else Timeout o circuito abierto
        AM-->>UC: error DownstreamServiceUnavailableException
        UC-->>EH: DownstreamServiceUnavailableException
        EH-->>C: 503 (la Transfer queda COMPENSATING)
    end

    Note over C,EH: Repetir con el mismo operationId devuelve lo mismo que la primera vez:<br/>COMPENSATED → 422 TransferRejected (transferId, transferStatus)<br/>COMPENSATING → 202 con la Transfer
```

## Notas

- **La ruta de la reversa lleva la operación original.** `reverse(debitCompleted.operationId(), ...)`
  envía `{op}-OUT` en `/movements/{operationId}/reversal`, que es lo que `ReverseMovementUseCaseImpl`
  de `account-service` busca en `account_operations`. `{op}-OUT-REV` (`forReversal()`) solo se usa como
  identificador del `TransactionReversal` que se anota en la pata local. (Corregido tras R10: antes se
  enviaba `{op}-OUT-REV` y contra el servicio real cada intento daba 404 → `COMPENSATION_FAILED`.)

- **La reversa es idempotente**: `account-service` identifica la reversa por la `operationId` de la
  pata original (`{op}-OUT`) y no la aplica dos veces; por eso el reintento es seguro. Devuelve el
  saldo, la comisión que hubiera cobrado y el contador de movimientos del mes, aunque la cuenta ya
  esté `INACTIVE`.
- **La comisión del retiro también se revierte.** Si la cuenta cobró comisión por la pata de salida, `{op}-OUT-FEE` pasa a `REVERSED` junto con la pata y la `Transfer` (`TransferLegs.saveCompensated`, una sola transacción de Mongo): la cuenta devolvió monto y comisión.
- **La pata de salida no se borra**: queda `REVERSED` con su `reversal` anotado; el historial del
  origen muestra el retiro y su reversa (regla 13, historial inmutable).
- **Retiro rechazado (paso 1).** Si el que falla es el retiro, no hay nada que compensar: la
  `Transfer` pasa de `STARTED` a `FAILED`, la pata `OUT` a `FAILED`, se publica `TransferFailed` y
  se responde 422 `TransferRejected` con el motivo del origen (p. ej. `INSUFFICIENT_FUNDS`).
- **Misma respuesta la primera vez y al repetir.** El caso de uso nunca convierte un rechazo en
  excepción: devuelve la `Transfer` y `TransfersController` decide por su estado (`FAILED` /
  `COMPENSATED` → 422 `TransferRejected` con `transferId` y `transferStatus`; `COMPENSATING` → 202).
  Es el mismo camino que la repetición del `operationId`. (Corregido en P2, paso 2.5: antes la primera
  petición respondía un `ErrorResponse` simple y un intento de reversa fallido daba 422 en vez de 202.)
