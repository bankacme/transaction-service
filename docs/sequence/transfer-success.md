# Secuencia — Transferencia exitosa (`POST /transfers`)

Saga **orquestada** por `StartTransferUseCaseImpl` (R3): retira del origen (`{op}-OUT`) y deposita
en el destino (`{op}-IN`), guardando cada paso junto con el estado de la `Transfer` en una
transacción de Mongo (`UnitOfWorkPort.saveTransferAndTransaction`, R4). Controller:
`TransfersController` (R5). El camino con rechazo y reversa está en `transfer-compensation.md`.

```mermaid
sequenceDiagram
    autonumber
    actor C as Cliente HTTP (TELLER/ADMIN/CUSTOMER)
    participant Ctrl as TransfersController
    participant UC as StartTransferUseCaseImpl
    participant TRepo as TransferRepositoryPort
    participant AL as AccountLookupPort
    participant Pol as TransferPolicy
    participant Dom as Transfer / Transaction
    participant UOW as UnitOfWorkPort
    participant AM as AccountMovementPort
    participant Acc as account-service
    participant Evt as TransactionEventPublisherPort
    participant EH as GlobalExceptionHandler

    C->>Ctrl: POST /api/v1/transfers (StartTransferRequest)
    Ctrl->>UC: execute(StartTransferCommand)
    UC->>TRepo: findByOperationId(op)

    alt operationId ya usado
        TRepo-->>UC: Transfer existente
        alt Otro origen, destino o monto
            UC-->>EH: BusinessRuleViolationException
            EH-->>C: 409 OPERATION_ID_REUSED
        else Coincide
            UC-->>Ctrl: la misma Transfer (estado actual, no se reejecuta)
            Ctrl-->>C: 200 (COMPLETED) / 202 (en curso) / 422 TransferRejected (FAILED, COMPENSATED)
        end
    else operationId nuevo
        TRepo-->>UC: vacío
        UC->>AL: findById(sourceAccountId)
        AL-->>UC: AccountSnapshot origen (o 404 ACCOUNT_NOT_FOUND)
        UC->>AL: findById(targetAccountId)
        AL-->>UC: AccountSnapshot destino (o 404 ACCOUNT_NOT_FOUND)
        UC->>Pol: evaluate(origen, destino, amount)
        alt Regla 5 incumplida
            Pol-->>UC: throws BusinessRuleViolationException
            UC-->>EH: SAME_ACCOUNT / ACCOUNT_INACTIVE / INVALID_AMOUNT
            EH-->>C: 422 (no se crea la Transfer)
        end
        Pol-->>UC: TransferKind (OWN si mismo cliente, si no THIRD_PARTY)

        Note over UC,Acc: Paso 1 — retiro del origen
        UC->>Dom: Transfer.start() + Transaction.pending({op}-OUT, TRANSFER_OUT)
        UC->>UOW: saveTransferAndTransaction(STARTED, OUT PENDING)
        UC->>AM: apply({op}-OUT, origen, TRANSFER_OUT, amount)
        AM->>Acc: POST /accounts/{origen}/movements (type = WITHDRAWAL)
        Acc-->>AM: 200 aplicado (resultingBalance, fee)
        AM-->>UC: MovementOutcome aplicado
        UC->>Dom: OUT.complete() + transfer.sourceDebited(OUT.id)
        UC->>UOW: saveTransferAndTransaction(SOURCE_DEBITED, OUT COMPLETED)

        Note over UC,Acc: Paso 2 — depósito en el destino
        UC->>Dom: Transaction.pending({op}-IN, TRANSFER_IN)
        UC->>UOW: saveTransferAndTransaction(SOURCE_DEBITED, IN PENDING)
        UC->>AM: apply({op}-IN, destino, TRANSFER_IN, amount)
        AM->>Acc: POST /accounts/{destino}/movements (type = DEPOSIT)
        Acc-->>AM: 200 aplicado
        AM-->>UC: MovementOutcome aplicado
        UC->>Dom: IN.complete() + transfer.completed(IN.id)
        UC->>UOW: saveTransferAndTransaction(COMPLETED, IN COMPLETED)
        UOW-->>UC: Transfer COMPLETED
        UC->>Evt: publish(TransferCompleted)
        UC-->>Ctrl: Transfer COMPLETED
        Ctrl-->>C: 201 Created (Transfer, kind)
    end
```

## Notas

- **Cada paso se persiste antes de llamar a `account-service`.** Si el proceso cae a mitad de la
  saga, la `Transfer` queda en `STARTED` o `SOURCE_DEBITED` y la retoma el job de recuperación con
  las mismas `operationId` de cada pata, que `account-service` reconoce como repetidas.
- **Cada cuenta aplica sus propias reglas y comisiones** a su pata (regla 10): el retiro puede
  cobrar comisión en el origen y el depósito en el destino. `TRANSFER_OUT`/`TRANSFER_IN` se envían a
  `account-service` como `WITHDRAWAL`/`DEPOSIT`, los únicos tipos que entiende.
- **La comisión de cada pata queda en el historial**: si la cuenta cobró, la pata se guarda junto con
  su `FEE` (`{op}-OUT-FEE` / `{op}-IN-FEE`, enlazada por `parentTransactionId`) en la misma transacción
  de Mongo que la `Transfer` (`TransferLegs.saveApplied`). Así el historial (y los reportes) explican
  el saldo.
- **Fallo técnico a mitad de camino** (timeout, circuito abierto): la petición responde 503 y la
  `Transfer` queda en el último estado guardado; no se pierde ni se duplica dinero.
- **P3:** las dos llamadas REST a `account-service` se reemplazan por los comandos
  `transaction.movement.requested` (tópico `account.command`) y sus respuestas
  `account.movement.applied/rejected`, sin tocar `Transfer`.
