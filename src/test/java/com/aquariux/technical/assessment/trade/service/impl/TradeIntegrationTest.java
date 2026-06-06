package com.aquariux.technical.assessment.trade.service.impl;

import com.aquariux.technical.assessment.trade.dto.internal.UserWalletDto;
import com.aquariux.technical.assessment.trade.dto.request.TradeRequest;
import com.aquariux.technical.assessment.trade.dto.response.TradeResponse;
import com.aquariux.technical.assessment.trade.entity.CryptoPair;
import com.aquariux.technical.assessment.trade.entity.CryptoPrice;
import com.aquariux.technical.assessment.trade.enums.TradeType;
import com.aquariux.technical.assessment.trade.mapper.CryptoPairMapper;
import com.aquariux.technical.assessment.trade.mapper.CryptoPriceMapper;
import com.aquariux.technical.assessment.trade.mapper.UserWalletMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

// Boots the full Spring context with the real H2 in-memory DB and Flyway migrations.
// @Transactional rolls back all DB changes after each test so tests don't affect each other.
@SpringBootTest
@Transactional
class TradeIntegrationTest {

    @Autowired
    private TradeServiceImpl tradeService;

    @Autowired
    private UserWalletMapper userWalletMapper;

    @Autowired
    private CryptoPairMapper cryptoPairMapper;

    @Autowired
    private CryptoPriceMapper cryptoPriceMapper;

    // Seeded by Flyway V3: user 1 has USDT=10000, no BTC wallet
    private static final Long USER_1 = 1L;
    // Seeded by Flyway V3: user 7 has USDT=20000 and BTC=0.5
    private static final Long USER_7 = 7L;
    // Symbol IDs from Flyway V1 schema
    private static final Long BTC_SYMBOL_ID  = 1L;
    private static final Long USDT_SYMBOL_ID = 3L;

    @Test
    void executeBuy_ShouldDeductUsdtAndCreateBtcWallet_WhenUserHasNoExistingBtcWallet() {
        // Given — read live DB state before the trade
        CryptoPair pair = cryptoPairMapper.findByPairName("BTCUSDT");
        CryptoPrice price = cryptoPriceMapper.findLatestPriceByCryptoPairId(pair.getId());

        BigDecimal quantity = new BigDecimal("0.001");
        BigDecimal expectedTotalCost = quantity.multiply(price.getAskPrice());

        UserWalletDto usdtBefore = userWalletMapper.findByUserIdAndSymbolId(USER_1, USDT_SYMBOL_ID);
        BigDecimal expectedUsdtAfter = usdtBefore.getBalance().subtract(expectedTotalCost);

        TradeRequest request = new TradeRequest();
        request.setUserId(USER_1);
        request.setPairName("BTCUSDT");
        request.setTradeType(TradeType.BUY);
        request.setQuantity(quantity);

        // When
        TradeResponse response = tradeService.executeTrade(request);

        // Then — response
        assertThat(response.getTradeId()).isNotNull();
        assertThat(response.getPrice()).isEqualByComparingTo(price.getAskPrice());
        assertThat(response.getTotalAmount()).isEqualByComparingTo(expectedTotalCost);

        // Then — USDT wallet was debited in the DB
        UserWalletDto usdtAfter = userWalletMapper.findByUserIdAndSymbolId(USER_1, USDT_SYMBOL_ID);
        assertThat(usdtAfter.getBalance()).isEqualByComparingTo(expectedUsdtAfter);

        // Then — BTC wallet was created with the purchased quantity as opening balance
        UserWalletDto btcWallet = userWalletMapper.findByUserIdAndSymbolId(USER_1, BTC_SYMBOL_ID);
        assertThat(btcWallet).isNotNull();
        assertThat(btcWallet.getBalance()).isEqualByComparingTo(quantity);
    }

    @Test
    void executeSell_ShouldDeductBtcAndCreditUsdt_WhenBothWalletsExist() {
        // Given — user 7 has BTC=0.5 and USDT=20000
        CryptoPair pair = cryptoPairMapper.findByPairName("BTCUSDT");
        CryptoPrice price = cryptoPriceMapper.findLatestPriceByCryptoPairId(pair.getId());

        BigDecimal quantity = new BigDecimal("0.1");
        BigDecimal expectedProceeds = quantity.multiply(price.getBidPrice());

        UserWalletDto btcBefore  = userWalletMapper.findByUserIdAndSymbolId(USER_7, BTC_SYMBOL_ID);
        UserWalletDto usdtBefore = userWalletMapper.findByUserIdAndSymbolId(USER_7, USDT_SYMBOL_ID);

        TradeRequest request = new TradeRequest();
        request.setUserId(USER_7);
        request.setPairName("BTCUSDT");
        request.setTradeType(TradeType.SELL);
        request.setQuantity(quantity);

        // When
        TradeResponse response = tradeService.executeTrade(request);

        // Then — response
        assertThat(response.getTradeId()).isNotNull();
        assertThat(response.getPrice()).isEqualByComparingTo(price.getBidPrice());
        assertThat(response.getTotalAmount()).isEqualByComparingTo(expectedProceeds);

        // Then — BTC wallet was debited in the DB
        UserWalletDto btcAfter = userWalletMapper.findByUserIdAndSymbolId(USER_7, BTC_SYMBOL_ID);
        assertThat(btcAfter.getBalance())
                .isEqualByComparingTo(btcBefore.getBalance().subtract(quantity));

        // Then — USDT wallet was credited in the DB
        UserWalletDto usdtAfter = userWalletMapper.findByUserIdAndSymbolId(USER_7, USDT_SYMBOL_ID);
        assertThat(usdtAfter.getBalance())
                .isEqualByComparingTo(usdtBefore.getBalance().add(expectedProceeds));
    }

    @Test
    void sequentialTrades_BuyThenSell_ShouldReflectCorrectBalancesAfterEachTrade() {
        // User 1 starts with USDT=10000 and no BTC wallet.
        // Trade 1: BUY 0.001 BTC  → USDT decreases, BTC wallet created
        // Trade 2: SELL 0.001 BTC → BTC back to 0, USDT partially restored (spread = ask - bid)

        CryptoPair pair = cryptoPairMapper.findByPairName("BTCUSDT");
        CryptoPrice price = cryptoPriceMapper.findLatestPriceByCryptoPairId(pair.getId());

        BigDecimal quantity = new BigDecimal("0.001");
        BigDecimal buyCost    = quantity.multiply(price.getAskPrice());
        BigDecimal sellProfit = quantity.multiply(price.getBidPrice());

        UserWalletDto usdtBefore = userWalletMapper.findByUserIdAndSymbolId(USER_1, USDT_SYMBOL_ID);

        // --- Trade 1: BUY ---
        TradeRequest buyRequest = new TradeRequest();
        buyRequest.setUserId(USER_1);
        buyRequest.setPairName("BTCUSDT");
        buyRequest.setTradeType(TradeType.BUY);
        buyRequest.setQuantity(quantity);
        tradeService.executeTrade(buyRequest);

        // Verify intermediate state after BUY
        UserWalletDto usdtMid = userWalletMapper.findByUserIdAndSymbolId(USER_1, USDT_SYMBOL_ID);
        UserWalletDto btcMid  = userWalletMapper.findByUserIdAndSymbolId(USER_1, BTC_SYMBOL_ID);
        assertThat(usdtMid.getBalance()).isEqualByComparingTo(usdtBefore.getBalance().subtract(buyCost));
        assertThat(btcMid.getBalance()).isEqualByComparingTo(quantity);

        // --- Trade 2: SELL ---
        TradeRequest sellRequest = new TradeRequest();
        sellRequest.setUserId(USER_1);
        sellRequest.setPairName("BTCUSDT");
        sellRequest.setTradeType(TradeType.SELL);
        sellRequest.setQuantity(quantity);
        tradeService.executeTrade(sellRequest);

        // Verify final state after SELL
        UserWalletDto usdtFinal = userWalletMapper.findByUserIdAndSymbolId(USER_1, USDT_SYMBOL_ID);
        UserWalletDto btcFinal  = userWalletMapper.findByUserIdAndSymbolId(USER_1, BTC_SYMBOL_ID);

        // USDT = original - buyCost + sellProfit (net loss is the bid-ask spread)
        BigDecimal expectedFinalUsdt = usdtBefore.getBalance().subtract(buyCost).add(sellProfit);
        assertThat(usdtFinal.getBalance()).isEqualByComparingTo(expectedFinalUsdt);

        // BTC balance drained back to zero after selling everything
        assertThat(btcFinal.getBalance()).isEqualByComparingTo(BigDecimal.ZERO);
    }
}