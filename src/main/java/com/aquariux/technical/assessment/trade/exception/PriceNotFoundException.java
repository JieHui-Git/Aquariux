package com.aquariux.technical.assessment.trade.exception;

public class PriceNotFoundException extends RuntimeException {

    public PriceNotFoundException(String pairName) {
        super("No price available for pair: " + pairName);
    }
}