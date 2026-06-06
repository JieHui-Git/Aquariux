package com.aquariux.technical.assessment.trade.dto.request;

import com.aquariux.technical.assessment.trade.enums.TradeType;
import lombok.Data;
import java.math.BigDecimal;

@Data
public class TradeRequest {
    private Long userId;
    private String pairName;
    private TradeType tradeType;
    private BigDecimal quantity;
}