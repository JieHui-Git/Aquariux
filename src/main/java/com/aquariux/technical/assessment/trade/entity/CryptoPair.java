package com.aquariux.technical.assessment.trade.entity;

import lombok.Data;

@Data
public class CryptoPair {
    private Long id;
    private Long baseSymbolId;
    private Long quoteSymbolId;
    private String pairName;
    private Boolean active;
}