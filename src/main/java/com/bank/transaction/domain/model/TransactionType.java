package com.bank.transaction.domain.model;

/** DEBIT_PAYMENT, YANKI_PAYMENT_OUT y YANKI_PAYMENT_IN son de P3 (ficha, sección 3.3), pero
 *  viven aquí desde ya: el enum completo no cuesta nada y evita un cambio de contrato luego. */
public enum TransactionType {
    DEPOSIT,
    WITHDRAWAL,
    TRANSFER_OUT,
    TRANSFER_IN,
    FEE,
    CREDIT_PAYMENT,
    CARD_PAYMENT,
    CARD_CHARGE,
    DEBIT_PAYMENT,
    YANKI_PAYMENT_OUT,
    YANKI_PAYMENT_IN
}
