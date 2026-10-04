# Secuencia — Registro de un pago de crédito (`POST /transactions/records`)

`credit-service` ya aplicó el pago sobre su propio producto (crédito o tarjeta); aquí solo se
**registra** en el historial, sin validar saldos ni llamar a `account-service` (regla 12). Interno
(`x-gateway-internal: true`). Implementado en `RecordExternalMovementUseCaseImpl` (R3) +
`InternalController` / `GlobalExceptionHandler` (R5). En P3 el mismo caso de uso se dispara desde el
evento `credit.payment.registered` (tópico `credit.operation`), sin cambiarlo.

```mermaid
sequenceDiagram
    autonumber
    participant Cr as credit-service
    participant K as Kafka (credit.operation)
    participant Cons as CreditEventsConsumer (P3)
    participant Ctrl as InternalController
    participant UC as RecordExternalMovementUseCaseImpl
    participant Repo as TransactionRepositoryPort
    participant Dom as Transaction (aggregate)
    participant Evt as TransactionEventPublisherPort
    participant EH as GlobalExceptionHandler

    alt P1/P2 — REST
        Cr->>Ctrl: POST /api/v1/transactions/records<br/>(CREDIT o CREDIT_CARD, CREDIT_PAYMENT, amount, resultingBalance)
        Ctrl->>UC: execute(RecordExternalMovementCommand)
    else P3 — evento (planificado)
        Cr->>K: credit.payment.registered (clave productId)
        K->>Cons: consume
        Cons->>UC: execute(RecordExternalMovementCommand)
    end

    UC->>Repo: findByOperationId(operationId)
    alt operationId ya registrado
        Repo-->>UC: Transaction existente
        alt Otro producto, tipo o monto
            UC-->>EH: BusinessRuleViolationException
            EH-->>Cr: 409 OPERATION_ID_REUSED
        else Coincide (duplicado)
            UC-->>Ctrl: la misma Transaction
            Ctrl-->>Cr: 200 OK (se ignora, no se publica nada)
        end
    else operationId nuevo
        Repo-->>UC: vacío
        UC->>Dom: Transaction.record(op, product, customerId, type, amount, resultingBalance)
        alt Tipo no corresponde al producto
            Dom-->>UC: throws BusinessRuleViolationException
            UC-->>EH: INVALID_TYPE_FOR_PRODUCT
            EH-->>Cr: 422 INVALID_TYPE_FOR_PRODUCT
        end
        Dom-->>UC: Transaction COMPLETED (nace ya aplicada, sin pasar por PENDING)
        UC->>Repo: save(recorded)
        UC->>Evt: publish(TransactionRegistered)
        UC-->>Ctrl: Transaction
        Ctrl-->>Cr: 201 Created (Transaction)
    end
```

## Notas

- **Tipos válidos por producto:** `CREDIT` → `CREDIT_PAYMENT`; `CREDIT_CARD` → `CARD_PAYMENT`
  (pago de la tarjeta) o `CARD_CHARGE` (consumo). Mismo flujo para los tres; en P3 el consumo llega
  como `credit.card.charge.registered`.
- **No hay saga ni reintento:** el efecto ya ocurrió en `credit-service`. Un duplicado (mismo
  `operationId`, mismos datos) se acepta sin crear otro registro, así que `credit-service` puede
  reintentar sin riesgo.
- **`resultingBalance`** aquí es el saldo pendiente del crédito o el monto usado de la tarjeta, no
  el saldo de una cuenta.
- **Pendiente conocido:** `Transaction.record` no recibe `occurredAt` ni `payerCustomerId`, aunque
  el contrato los manda. `occurredAt` se guarda con la hora de registro en este servicio (no la del
  pago real) y el pagador tercero se pierde. Cerrarlo requiere ensanchar la factory `record(...)`
  y `RecordExternalMovementCommand`.
- **P3:** `CreditEventsConsumer` todavía no existe (`infrastructure/adapter/in/kafka/` solo tiene su
  `package-info.java`); se dibuja como referencia del diseño.
