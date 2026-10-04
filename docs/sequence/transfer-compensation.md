# Secuencia — Transferencia con compensación (`POST /transfers`)

Mismo inicio que `transfer-success.md` hasta `SOURCE_DEBITED`. Aquí el depósito en el destino es
rechazado y la saga **revierte** el retiro del origen (regla 8). Implementado en
`StartTransferUseCaseImpl.onCreditRejected` / `reverseDebit` (R3) y en
`AccountMovementClient.reverse` (R7).

```mermaid
sequenceDiagram
    autonumber
    actor C as Cliente HTTP
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
    Note over AM,Acc: la ruta lleva la operationId ORIGINAL;<br/>{op}-OUT-REV solo se usa en la anotación local
    alt Reversa aplicada
        Acc-->>AM: 200 (saldo, comisión y contador devueltos)
        AM-->>UC: MovementOutcome aplicado
        UC->>Dom: OUT.markReversed(REVERSED) + transfer.compensated()
        UC->>UOW: saveTransferAndTransaction(COMPENSATED, OUT REVERSED)
        UC->>Evt: publish(TransferFailed)
        UC-->>EH: BusinessRuleViolationException (código del depósito rechazado)
        EH-->>C: 422 ErrorResponse (code = motivo del destino)
    else Reversa rechazada
        Acc-->>AM: 404/422
        AM-->>UC: MovementOutcome rechazado
        UC->>Dom: OUT.markReversalFailed(REVERSAL_FAILED, code) + transfer.compensationAttemptFailed()
        UC->>UOW: saveTransferAndTransaction(COMPENSATING, attempts + 1)
        Note over UC: sin evento: sigue en curso
        UC-->>EH: BusinessRuleViolationException (código del depósito rechazado)
        EH-->>C: 422 ErrorResponse
        Note over UC,Acc: el job de recuperación reintenta la reversa<br/>hasta transfer.compensation.max-attempts (5)<br/>y entonces pasa a COMPENSATION_FAILED
    else Timeout o circuito abierto
        AM-->>UC: error DownstreamServiceUnavailableException
        UC-->>EH: DownstreamServiceUnavailableException
        EH-->>C: 503 (la Transfer queda COMPENSATING)
    end

    Note over C,EH: Repetir con el mismo operationId devuelve el estado guardado:<br/>COMPENSATED → 422 TransferRejected (transferId, transferStatus)<br/>COMPENSATING → 202 con la Transfer
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
- **La pata de salida no se borra**: queda `REVERSED` con su `reversal` anotado; el historial del
  origen muestra el retiro y su reversa (regla 13, historial inmutable).
- **Retiro rechazado (paso 1).** Si el que falla es el retiro, no hay nada que compensar: la
  `Transfer` pasa de `STARTED` a `FAILED`, la pata `OUT` a `FAILED`, se publica `TransferFailed` y
  se responde 422 con el motivo del origen (p. ej. `INSUFFICIENT_FUNDS`).
- **Diferencia con el contrato (pendiente).** En la primera petición un rechazo se propaga como
  `BusinessRuleViolationException` y el cuerpo es un `ErrorResponse` simple, sin `transferId` ni
  `transferStatus`; solo la repetición pasa por `TransferRejectedException` y devuelve el cuerpo
  `TransferRejected` completo. Del mismo modo, un intento de reversa fallido responde 422 aunque la
  `Transfer` siga `COMPENSATING` (el contrato pide 202 en ese estado). Las pruebas de
  `TransfersControllerTest` simulan el caso de uso devolviendo la `Transfer` ya rechazada, por eso
  no lo detectan.
