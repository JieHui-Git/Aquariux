package com.aquariux.technical.assessment.trade.service.impl;

import com.aquariux.technical.assessment.trade.dto.request.TradeRequest;
import com.aquariux.technical.assessment.trade.dto.response.TradeResponse;
import com.aquariux.technical.assessment.trade.entity.CryptoPair;
import com.aquariux.technical.assessment.trade.entity.CryptoPrice;
import com.aquariux.technical.assessment.trade.entity.Trade;
import com.aquariux.technical.assessment.trade.enums.TradeType;
import com.aquariux.technical.assessment.trade.exception.InsufficientBalanceException;
import com.aquariux.technical.assessment.trade.exception.InvalidTradePairException;
import com.aquariux.technical.assessment.trade.exception.PriceNotFoundException;
import com.aquariux.technical.assessment.trade.mapper.CryptoPairMapper;
import com.aquariux.technical.assessment.trade.mapper.CryptoPriceMapper;
import com.aquariux.technical.assessment.trade.mapper.TradeMapper;
import com.aquariux.technical.assessment.trade.mapper.UserWalletMapper;
import com.aquariux.technical.assessment.trade.service.TradeServiceInterface;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class TradeServiceImpl implements TradeServiceInterface {

    private final TradeMapper tradeMapper;
    private final CryptoPairMapper cryptoPairMapper;
    private final CryptoPriceMapper cryptoPriceMapper;
    private final UserWalletMapper userWalletMapper;

    @Override
    @Transactional
    public TradeResponse executeTrade(TradeRequest request) {
        // Step 1: Validate all required fields
        validateRequest(request);

        // Step 2: Verify the trading pair exists, is active, and retrieve base/quote symbol IDs
        CryptoPair pair = cryptoPairMapper.findByPairName(request.getPairName());
        if (pair == null || !pair.getActive()) {
            throw new InvalidTradePairException(request.getPairName());
        }

        // Step 3: Get the latest aggregated price for this pair
        CryptoPrice price = cryptoPriceMapper.findLatestPriceByCryptoPairId(pair.getId());
        if (price == null) {
            throw new PriceNotFoundException(request.getPairName());
        }

        // Step 4: BUY executes at ask price (what sellers are asking),
        //         SELL executes at bid price (what buyers are bidding)
        BigDecimal executionPrice = request.getTradeType() == TradeType.BUY
                ? price.getAskPrice()
                : price.getBidPrice();

        // Step 5: Calculate the total USDT cost (BUY) or proceeds (SELL)
        BigDecimal totalAmount = request.getQuantity().multiply(executionPrice);

        // Step 6: Update wallets — debit the spend currency, credit the receive currency
        if (request.getTradeType() == TradeType.BUY) {
            executeBuy(request.getUserId(), pair.getBaseSymbolId(), pair.getQuoteSymbolId(),
                    request.getQuantity(), totalAmount);
        } else {
            executeSell(request.getUserId(), pair.getBaseSymbolId(), pair.getQuoteSymbolId(),
                    request.getQuantity(), totalAmount);
        }

        // Step 7: Persist the trade record — id is written back into the entity after insert
        Trade trade = new Trade();
        trade.setUserId(request.getUserId());
        trade.setCryptoPairId(pair.getId());
        trade.setTradeType(request.getTradeType().name());
        trade.setQuantity(request.getQuantity());
        trade.setPrice(executionPrice);
        trade.setTotalAmount(totalAmount);
        trade.setTradeTime(LocalDateTime.now());
        tradeMapper.insertTrade(trade);

        // Step 8: Build and return the response
        TradeResponse response = new TradeResponse();
        response.setTradeId(trade.getId());
        response.setUserId(request.getUserId());
        response.setPairName(request.getPairName());
        response.setTradeType(request.getTradeType().name());
        response.setQuantity(request.getQuantity());
        response.setPrice(executionPrice);
        response.setTotalAmount(totalAmount);
        response.setTradeTime(trade.getTradeTime());
        return response;
    }

    // Validates that all required fields are present and quantity is positive
    private void validateRequest(TradeRequest request) {
        if (request.getUserId() == null || request.getPairName() == null
                || request.getTradeType() == null || request.getQuantity() == null) {
            throw new IllegalArgumentException("userId, pairName, tradeType and quantity are required");
        }
        if (request.getQuantity().compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Quantity must be greater than zero");
        }
    }

    // Handles wallet updates for a BUY trade:
    // user spends USDT (quote) and receives base currency (e.g. BTC or ETH)
    private void executeBuy(Long userId, Long baseSymbolId, Long quoteSymbolId,
                            BigDecimal quantity, BigDecimal totalAmount) {
        // a. Get the user's USDT wallet and check they have enough to cover the total cost
        var quoteWallet = userWalletMapper.findByUserIdAndSymbolId(userId, quoteSymbolId);
        if (quoteWallet == null || quoteWallet.getBalance().compareTo(totalAmount) < 0) {
            throw new InsufficientBalanceException("USDT");
        }

        // b. Deduct the total cost from USDT wallet
        BigDecimal newQuoteBalance =  quoteWallet.getBalance().subtract(totalAmount);
        userWalletMapper.updateBalance(quoteWallet.getId(), newQuoteBalance);

        // c. Credit the base currency wallet — create it if this is the user's first time holding it
        var baseWallet = userWalletMapper.findByUserIdAndSymbolId(userId, baseSymbolId);
        if (baseWallet == null) {
            userWalletMapper.insertWallet(userId, baseSymbolId, quantity);
        } else {
            BigDecimal newBaseBalance = baseWallet.getBalance().add(quantity);
            userWalletMapper.updateBalance(baseWallet.getId(), newBaseBalance);
        }
    }

    // Handles wallet updates for a SELL trade:
    // user spends base currency (e.g. BTC or ETH) and receives USDT (quote)
    private void executeSell(Long userId, Long baseSymbolId, Long quoteSymbolId,
                             BigDecimal quantity, BigDecimal totalAmount) {
        // a. Get the user's base currency wallet and check they have enough to sell
        var baseWallet = userWalletMapper.findByUserIdAndSymbolId(userId, baseSymbolId);
        if (baseWallet == null || baseWallet.getBalance().compareTo(quantity) < 0) {
            throw new InsufficientBalanceException(baseWallet != null ? baseWallet.getSymbol() : "base currency");
        }

        // b. Deduct the sold quantity from the base currency wallet
        BigDecimal newBaseBalance = baseWallet.getBalance().subtract(quantity);
        userWalletMapper.updateBalance(baseWallet.getId(), newBaseBalance);

        // c. Credit USDT wallet — create it if this is the user's first time holding USDT
        var quoteWallet = userWalletMapper.findByUserIdAndSymbolId(userId, quoteSymbolId);
        if (quoteWallet == null) {
            userWalletMapper.insertWallet(userId, quoteSymbolId, totalAmount);
        } else {
            BigDecimal newQuoteBalance =  quoteWallet.getBalance().add(totalAmount);
            userWalletMapper.updateBalance(quoteWallet.getId(), newQuoteBalance);
        }
    }
}