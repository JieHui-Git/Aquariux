package com.aquariux.technical.assessment.trade.exception;

import com.aquariux.technical.assessment.trade.dto.response.ErrorResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.LocalDateTime;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(InvalidTradePairException.class)
    public ResponseEntity<ErrorResponse> handleInvalidTradePair(InvalidTradePairException ex) {
        return ResponseEntity.badRequest().body(buildError("INVALID_TRADE_PAIR", ex.getMessage()));
    }

    @ExceptionHandler(PriceNotFoundException.class)
    public ResponseEntity<ErrorResponse> handlePriceNotFound(PriceNotFoundException ex) {
        return ResponseEntity.unprocessableEntity().body(buildError("PRICE_NOT_AVAILABLE", ex.getMessage()));
    }

    @ExceptionHandler(InsufficientBalanceException.class)
    public ResponseEntity<ErrorResponse> handleInsufficientBalance(InsufficientBalanceException ex) {
        return ResponseEntity.unprocessableEntity().body(buildError("INSUFFICIENT_BALANCE", ex.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgument(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(buildError("INVALID_REQUEST", ex.getMessage()));
    }

    private ErrorResponse buildError(String error, String message) {
        ErrorResponse response = new ErrorResponse();
        response.setError(error);
        response.setMessage(message);
        response.setTimestamp(LocalDateTime.now());
        return response;
    }
}