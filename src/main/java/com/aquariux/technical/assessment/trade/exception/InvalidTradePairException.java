package com.aquariux.technical.assessment.trade.exception;

public class InvalidTradePairException extends RuntimeException {

    public InvalidTradePairException(String pairName) {
        super("Invalid trading pair: " + pairName);
    }
}