package com.aquariux.technical.assessment.trade.exception;

public class InsufficientBalanceException extends RuntimeException {

    public InsufficientBalanceException(String symbol) {
        super("Insufficient " + symbol + " balance");
    }
}