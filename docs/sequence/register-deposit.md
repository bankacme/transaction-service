# Secuencia — Depósito con comisión e idempotencia (`POST /deposits`)

Implementado en `RegisterDepositUseCaseImpl` → `AbstractRegisterMovementUseCase` (R3, compartido
con el retiro), `AccountLookupClient` / `AccountMovementClient` (R7), `MongoUnitOfWorkAdapter` (R4) y
`DepositsAndWithdrawalsController` / `GlobalExceptionHandler` (R5). El retiro (`POST /withdrawals`)
sigue exactamente el mismo flujo con `type = WITHDRAWAL`.

```mermaid
sequenceDiagram
    autonumber
    actor C as Cliente HTTP (TELLER/ADMIN/CUSTOMER)
    participant Ctrl as DepositsAndWithdrawalsController
    participant UC as RegisterDepositUseCaseImpl
    participant Repo as TransactionRepositoryPort
    participant AL as AccountLookupPort (AccountLookupClient)
    participant AM as AccountMovementPort (AccountMovementClient)
    participant Acc as account-service
    participant Dom as Transaction (aggregate)
    participant UOW as UnitOfWorkPort
    participant Evt as TransactionEventPublisherPort
    participant EH as GlobalExceptionHandler

    C->>Ctrl: POST /api/v1/deposits (MovementRequest)
    Note over Ctrl: Bean Validation del DTO: operationId 8-56, amount > 0
    alt DTO inválido
        Ctrl-->>EH: WebExchangeBindException
        EH-->>C: 400 VALIDATION_ERROR
    end
    Note over Ctrl: guarda requestReceivedAt = clock.instant()
    Ctrl->>UC: execute(RegisterMovementCommand)
    UC->>Repo: findByOperationId(operationId)

    alt operationId ya usado
        Repo-->>UC: Transaction existente
        alt Otra cuenta, tipo o monto
            UC-->>Ctrl: error BusinessRuleViolationException
            Ctrl-->>EH: OPERATION_ID_REUSED
            EH-->>C: 409 OPERATION_ID_REUSED
        else Coincide
            UC-->>Ctrl: la misma Transaction (sin volver a llamar a account-service)
            Note over Ctrl: createdAt < requestReceivedAt → repetición
            Ctrl-->>C: 200 (COMPLETED) / 202 (PENDING) / 422 MovementRejected (FAILED)
        end
    else operationId nuevo
        Repo-->>UC: vacío
        UC->>AL: findById(accountId)
        AL->>Acc: GET /accounts/{id} (circuit breaker + 2 s)
        alt Cuenta no existe
            Acc-->>AL: 404
            AL-->>UC: Maybe vacío
            UC-->>Ctrl: error AccountNotFoundException
            Ctrl-->>EH: AccountNotFoundException
            EH-->>C: 404 ACCOUNT_NOT_FOUND
        end
        Acc-->>AL: 200 Account
        AL-->>UC: AccountSnapshot
        UC->>Dom: Transaction.pending(op, ACCOUNT, customerId, DEPOSIT, amount)
        Dom-->>UC: Transaction PENDING
        UC->>Repo: save(pending)
        Note over Repo: queda registrado ANTES de mover el saldo:<br/>si lo de abajo se cae, el reintento lo retoma
        UC->>AM: apply(op, accountId, DEPOSIT, amount, today)
        AM->>Acc: POST /accounts/{id}/movements
        alt Timeout, circuito abierto o 5xx
            AM-->>UC: error DownstreamServiceUnavailableException
            UC-->>Ctrl: error
            Ctrl-->>EH: DownstreamServiceUnavailableException
            EH-->>C: 503 SERVICE_UNAVAILABLE (la Transaction sigue PENDING)
        else 404/422 con ErrorResponse (rechazo de negocio)
            Acc-->>AM: 422 (p. ej. ACCOUNT_INACTIVE, MONTHLY_LIMIT_EXCEEDED)
            AM-->>UC: MovementOutcome rechazado (no es una excepción)
            UC->>Dom: fail(failureReason)
            Dom-->>UC: Transaction FAILED
            UC->>Repo: save(failed)
            UC->>Evt: publish(TransactionFailed)
            UC-->>Ctrl: Transaction FAILED
            Ctrl-->>EH: MovementRejectedException
            EH-->>C: 422 MovementRejected (code = motivo, transactionId)
        else 200 aplicado
            Acc-->>AM: 200 (resultingBalance, fee)
            AM-->>UC: MovementOutcome aplicado
            UC->>Dom: complete(resultingBalance)
            Dom-->>UC: Transaction COMPLETED
            alt Sin comisión (fee nulo o 0)
                UC->>Repo: save(completed)
                UC->>Evt: publish(TransactionRegistered)
            else Con comisión
                UC->>Dom: Transaction.record({op}-FEE, FEE, fee, resultingBalance)
                Dom-->>UC: Transaction FEE COMPLETED
                UC->>UOW: saveTransactionAndFee(completed, feeTx)
                Note over UOW: transacción de Mongo: el depósito y su FEE<br/>se guardan juntos o no se guarda ninguno
                UOW-->>UC: Transaction guardada
                UC->>Evt: publish(TransactionRegistered del depósito, con la comisión)
                UC->>Evt: publish(TransactionRegistered del FEE)
            end
            UC-->>Ctrl: Transaction COMPLETED
            Note over Ctrl: createdAt ≥ requestReceivedAt → recién creada
            Ctrl-->>C: 201 Created (MovementResponse)
        end
    end
```

## Notas

- **Idempotencia en dos niveles.** Aquí, por `operationId` (índice único `uk_tx_operation_id`, con
  recuperación de carrera en `TransactionPersistenceAdapter`); y en `account-service`, que recibe la
  misma `operationId` y no vuelve a mover el saldo. Por eso es seguro que el job de recuperación
  reintente un `PENDING` (ver `recover-pending.md`).
- **200 vs 201.** Los casos de uso devuelven el aggregate sin una bandera de "nuevo"; el controller
  lo deduce comparando `createdAt` con el instante en que recibió la petición
  (`TransactionRestMapper.wasCreatedByThisRequest`).
- **Un rechazo no es un error.** `AccountMovementClient` traduce cualquier 404/422 con cuerpo
  `ErrorResponse` a un `MovementOutcome` rechazado; solo un fallo técnico (timeout, circuito
  abierto, 5xx) se vuelve `DownstreamServiceUnavailableException` y cuenta para el circuit breaker.
- **La comisión la decide la cuenta, no este servicio.** `account-service` cobra `transactionFee`
  cuando el movimiento supera las transacciones libres del mes (también en depósitos) y la devuelve
  en `fee`; aquí solo se registra como `FEE` con `operationId` `{op}-FEE` (regla 11).
- **Pendiente conocido:** `MovementResponse.feeTransaction` no se completa (el puerto solo devuelve
  el movimiento padre) y la `Transaction` `FEE` no lleva `parentTransactionId`; se relaciona con el
  padre por el sufijo `-FEE` de su `operationId`.
