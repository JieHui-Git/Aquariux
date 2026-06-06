package com.aquariux.technical.assessment.trade.service.impl;

import com.aquariux.technical.assessment.trade.dto.internal.UserWalletDto;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TradeServiceImplTest {

    @Mock
    private TradeMapper tradeMapper;

    @Mock
    private CryptoPairMapper cryptoPairMapper;

    @Mock
    private CryptoPriceMapper cryptoPriceMapper;

    @Mock
    private UserWalletMapper userWalletMapper;

    @InjectMocks
    private TradeServiceImpl tradeService;

    private CryptoPair btcPair;
    private CryptoPrice btcPrice;
    private UserWalletDto usdtWallet;
    private UserWalletDto btcWallet;

    @BeforeEach
    void setUp() {
        btcPair = new CryptoPair();
        btcPair.setId(1L);
        btcPair.setBaseSymbolId(1L);   // BTC
        btcPair.setQuoteSymbolId(3L);  // USDT
        btcPair.setPairName("BTCUSDT");
        btcPair.setActive(true);

        btcPrice = new CryptoPrice();
        btcPrice.setCryptoPairId(1L);
        btcPrice.setBidPrice(new BigDecimal("50000.00"));
        btcPrice.setAskPrice(new BigDecimal("50100.00"));

        usdtWallet = new UserWalletDto();
        usdtWallet.setId(3L);
        usdtWallet.setUserId(1L);
        usdtWallet.setSymbolId(3L);
        usdtWallet.setBalance(new BigDecimal("10000.00"));
        usdtWallet.setSymbol("USDT");

        btcWallet = new UserWalletDto();
        btcWallet.setId(1L);
        btcWallet.setUserId(1L);
        btcWallet.setSymbolId(1L);
        btcWallet.setBalance(new BigDecimal("0.5"));
        btcWallet.setSymbol("BTC");
    }

    // --- BUY happy path ---

    @Test
    void executeTrade_Buy_ShouldDeductUsdtAndCreditBtc_WhenBothWalletsExist() {
        // Given
        TradeRequest request = new TradeRequest();
        request.setUserId(1L);
        request.setPairName("BTCUSDT");
        request.setTradeType(TradeType.BUY);
        request.setQuantity(new BigDecimal("0.1"));

        when(cryptoPairMapper.findByPairName("BTCUSDT")).thenReturn(btcPair);
        when(cryptoPriceMapper.findLatestPriceByCryptoPairId(1L)).thenReturn(btcPrice);
        when(userWalletMapper.findByUserIdAndSymbolId(1L, 3L)).thenReturn(usdtWallet); // quote (USDT)
        when(userWalletMapper.findByUserIdAndSymbolId(1L, 1L)).thenReturn(btcWallet);  // base (BTC)
        doAnswer(invocation -> {
            ((Trade) invocation.getArgument(0)).setId(100L);
            return null;
        }).when(tradeMapper).insertTrade(any(Trade.class));

        // When
        TradeResponse result = tradeService.executeTrade(request);

        // Then — response fields
        assertThat(result.getTradeId()).isEqualTo(100L);
        assertThat(result.getUserId()).isEqualTo(1L);
        assertThat(result.getPairName()).isEqualTo("BTCUSDT");
        assertThat(result.getTradeType()).isEqualTo("BUY");
        assertThat(result.getQuantity()).isEqualByComparingTo("0.1");
        assertThat(result.getPrice()).isEqualByComparingTo("50100.00");      // ask price used for BUY
        assertThat(result.getTotalAmount()).isEqualByComparingTo("5010.00"); // 0.1 * 50100

        // Then — wallet mutations
        verify(userWalletMapper).updateBalance(eq(3L), argThat(b -> b.compareTo(new BigDecimal("4990")) == 0)); // USDT 10000 - 5010
        verify(userWalletMapper).updateBalance(eq(1L), argThat(b -> b.compareTo(new BigDecimal("0.6")) == 0));  // BTC  0.5   + 0.1
        verify(tradeMapper).insertTrade(any(Trade.class));
    }

    @Test
    void executeTrade_Buy_ShouldCreateBtcWallet_WhenBaseWalletDoesNotExist() {
        // Given
        TradeRequest request = new TradeRequest();
        request.setUserId(1L);
        request.setPairName("BTCUSDT");
        request.setTradeType(TradeType.BUY);
        request.setQuantity(new BigDecimal("0.1"));

        when(cryptoPairMapper.findByPairName("BTCUSDT")).thenReturn(btcPair);
        when(cryptoPriceMapper.findLatestPriceByCryptoPairId(1L)).thenReturn(btcPrice);
        when(userWalletMapper.findByUserIdAndSymbolId(1L, 3L)).thenReturn(usdtWallet); // USDT wallet exists
        when(userWalletMapper.findByUserIdAndSymbolId(1L, 1L)).thenReturn(null);        // no BTC wallet yet
        doAnswer(invocation -> {
            ((Trade) invocation.getArgument(0)).setId(101L);
            return null;
        }).when(tradeMapper).insertTrade(any(Trade.class));

        // When
        TradeResponse result = tradeService.executeTrade(request);

        // Then
        assertThat(result.getTradeId()).isEqualTo(101L);
        assertThat(result.getTotalAmount()).isEqualByComparingTo("5010.00");

        // USDT debited as normal
        verify(userWalletMapper).updateBalance(eq(3L), argThat(b -> b.compareTo(new BigDecimal("4990")) == 0));
        // BTC wallet created with the purchased quantity as the opening balance
        verify(userWalletMapper).insertWallet(eq(1L), eq(1L), argThat(b -> b.compareTo(new BigDecimal("0.1")) == 0));
    }

    // --- SELL happy path ---

    @Test
    void executeTrade_Sell_ShouldDeductBtcAndCreditUsdt_WhenBothWalletsExist() {
        // Given
        TradeRequest request = new TradeRequest();
        request.setUserId(1L);
        request.setPairName("BTCUSDT");
        request.setTradeType(TradeType.SELL);
        request.setQuantity(new BigDecimal("0.1"));

        when(cryptoPairMapper.findByPairName("BTCUSDT")).thenReturn(btcPair);
        when(cryptoPriceMapper.findLatestPriceByCryptoPairId(1L)).thenReturn(btcPrice);
        when(userWalletMapper.findByUserIdAndSymbolId(1L, 1L)).thenReturn(btcWallet);   // base (BTC)
        when(userWalletMapper.findByUserIdAndSymbolId(1L, 3L)).thenReturn(usdtWallet);  // quote (USDT)
        doAnswer(invocation -> {
            ((Trade) invocation.getArgument(0)).setId(102L);
            return null;
        }).when(tradeMapper).insertTrade(any(Trade.class));

        // When
        TradeResponse result = tradeService.executeTrade(request);

        // Then — response fields
        assertThat(result.getTradeId()).isEqualTo(102L);
        assertThat(result.getTradeType()).isEqualTo("SELL");
        assertThat(result.getPrice()).isEqualByComparingTo("50000.00");      // bid price used for SELL
        assertThat(result.getTotalAmount()).isEqualByComparingTo("5000.00"); // 0.1 * 50000

        // Then — wallet mutations
        verify(userWalletMapper).updateBalance(eq(1L), argThat(b -> b.compareTo(new BigDecimal("0.4")) == 0));   // BTC  0.5   - 0.1
        verify(userWalletMapper).updateBalance(eq(3L), argThat(b -> b.compareTo(new BigDecimal("15000")) == 0)); // USDT 10000 + 5000
    }

    @Test
    void executeTrade_Sell_ShouldCreateUsdtWallet_WhenQuoteWalletDoesNotExist() {
        // Given
        TradeRequest request = new TradeRequest();
        request.setUserId(1L);
        request.setPairName("BTCUSDT");
        request.setTradeType(TradeType.SELL);
        request.setQuantity(new BigDecimal("0.1"));

        when(cryptoPairMapper.findByPairName("BTCUSDT")).thenReturn(btcPair);
        when(cryptoPriceMapper.findLatestPriceByCryptoPairId(1L)).thenReturn(btcPrice);
        when(userWalletMapper.findByUserIdAndSymbolId(1L, 1L)).thenReturn(btcWallet); // BTC wallet exists
        when(userWalletMapper.findByUserIdAndSymbolId(1L, 3L)).thenReturn(null);       // no USDT wallet yet
        doAnswer(invocation -> {
            ((Trade) invocation.getArgument(0)).setId(103L);
            return null;
        }).when(tradeMapper).insertTrade(any(Trade.class));

        // When
        TradeResponse result = tradeService.executeTrade(request);

        // Then
        assertThat(result.getTradeId()).isEqualTo(103L);
        assertThat(result.getTotalAmount()).isEqualByComparingTo("5000.00");

        // BTC debited as normal
        verify(userWalletMapper).updateBalance(eq(1L), argThat(b -> b.compareTo(new BigDecimal("0.4")) == 0));
        // USDT wallet created with the sale proceeds as the opening balance
        verify(userWalletMapper).insertWallet(eq(1L), eq(3L), argThat(b -> b.compareTo(new BigDecimal("5000")) == 0));
    }

    // --- Validation failures ---

    @Test
    void executeTrade_ShouldThrowIllegalArgument_WhenUserIdIsNull() {
        // Given
        TradeRequest request = new TradeRequest();
        request.setUserId(null);
        request.setPairName("BTCUSDT");
        request.setTradeType(TradeType.BUY);
        request.setQuantity(new BigDecimal("0.1"));

        // When / Then
        assertThatThrownBy(() -> tradeService.executeTrade(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("userId, pairName, tradeType and quantity are required");
    }

    @Test
    void executeTrade_ShouldThrowIllegalArgument_WhenQuantityIsZero() {
        // Given
        TradeRequest request = new TradeRequest();
        request.setUserId(1L);
        request.setPairName("BTCUSDT");
        request.setTradeType(TradeType.BUY);
        request.setQuantity(BigDecimal.ZERO);

        // When / Then
        assertThatThrownBy(() -> tradeService.executeTrade(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Quantity must be greater than zero");
    }

    @Test
    void executeTrade_ShouldThrowIllegalArgument_WhenQuantityIsNegative() {
        // Given
        TradeRequest request = new TradeRequest();
        request.setUserId(1L);
        request.setPairName("BTCUSDT");
        request.setTradeType(TradeType.BUY);
        request.setQuantity(new BigDecimal("-0.5"));

        // When / Then
        assertThatThrownBy(() -> tradeService.executeTrade(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Quantity must be greater than zero");
    }

    // --- Invalid pair ---

    @Test
    void executeTrade_ShouldThrowInvalidTradePair_WhenPairNotFound() {
        // Given
        TradeRequest request = new TradeRequest();
        request.setUserId(1L);
        request.setPairName("XYZUSDT");
        request.setTradeType(TradeType.BUY);
        request.setQuantity(new BigDecimal("1"));

        when(cryptoPairMapper.findByPairName("XYZUSDT")).thenReturn(null);

        // When / Then
        assertThatThrownBy(() -> tradeService.executeTrade(request))
                .isInstanceOf(InvalidTradePairException.class)
                .hasMessageContaining("XYZUSDT");
    }

    @Test
    void executeTrade_ShouldThrowInvalidTradePair_WhenPairIsInactive() {
        // Given
        TradeRequest request = new TradeRequest();
        request.setUserId(1L);
        request.setPairName("BTCUSDT");
        request.setTradeType(TradeType.BUY);
        request.setQuantity(new BigDecimal("1"));

        btcPair.setActive(false);
        when(cryptoPairMapper.findByPairName("BTCUSDT")).thenReturn(btcPair);

        // When / Then
        assertThatThrownBy(() -> tradeService.executeTrade(request))
                .isInstanceOf(InvalidTradePairException.class)
                .hasMessageContaining("BTCUSDT");
    }

    // --- No price available ---

    @Test
    void executeTrade_ShouldThrowPriceNotFound_WhenNoPriceAvailable() {
        // Given
        TradeRequest request = new TradeRequest();
        request.setUserId(1L);
        request.setPairName("BTCUSDT");
        request.setTradeType(TradeType.BUY);
        request.setQuantity(new BigDecimal("1"));

        when(cryptoPairMapper.findByPairName("BTCUSDT")).thenReturn(btcPair);
        when(cryptoPriceMapper.findLatestPriceByCryptoPairId(1L)).thenReturn(null);

        // When / Then
        assertThatThrownBy(() -> tradeService.executeTrade(request))
                .isInstanceOf(PriceNotFoundException.class)
                .hasMessageContaining("BTCUSDT");
    }

    // --- Insufficient balance ---

    @Test
    void executeTrade_Buy_ShouldThrowInsufficientBalance_WhenUsdtWalletDoesNotExist() {
        // Given
        TradeRequest request = new TradeRequest();
        request.setUserId(1L);
        request.setPairName("BTCUSDT");
        request.setTradeType(TradeType.BUY);
        request.setQuantity(new BigDecimal("0.1"));

        when(cryptoPairMapper.findByPairName("BTCUSDT")).thenReturn(btcPair);
        when(cryptoPriceMapper.findLatestPriceByCryptoPairId(1L)).thenReturn(btcPrice);
        when(userWalletMapper.findByUserIdAndSymbolId(1L, 3L)).thenReturn(null); // no USDT wallet

        // When / Then
        assertThatThrownBy(() -> tradeService.executeTrade(request))
                .isInstanceOf(InsufficientBalanceException.class)
                .hasMessageContaining("USDT");
    }

    @Test
    void executeTrade_Buy_ShouldThrowInsufficientBalance_WhenUsdtBalanceTooLow() {
        // Given
        TradeRequest request = new TradeRequest();
        request.setUserId(1L);
        request.setPairName("BTCUSDT");
        request.setTradeType(TradeType.BUY);
        request.setQuantity(new BigDecimal("0.1")); // costs 5010 USDT at ask price

        usdtWallet.setBalance(new BigDecimal("100.00")); // only 100 USDT
        when(cryptoPairMapper.findByPairName("BTCUSDT")).thenReturn(btcPair);
        when(cryptoPriceMapper.findLatestPriceByCryptoPairId(1L)).thenReturn(btcPrice);
        when(userWalletMapper.findByUserIdAndSymbolId(1L, 3L)).thenReturn(usdtWallet);

        // When / Then
        assertThatThrownBy(() -> tradeService.executeTrade(request))
                .isInstanceOf(InsufficientBalanceException.class)
                .hasMessageContaining("USDT");
    }

    @Test
    void executeTrade_Sell_ShouldThrowInsufficientBalance_WhenBtcWalletDoesNotExist() {
        // Given
        TradeRequest request = new TradeRequest();
        request.setUserId(1L);
        request.setPairName("BTCUSDT");
        request.setTradeType(TradeType.SELL);
        request.setQuantity(new BigDecimal("0.1"));

        when(cryptoPairMapper.findByPairName("BTCUSDT")).thenReturn(btcPair);
        when(cryptoPriceMapper.findLatestPriceByCryptoPairId(1L)).thenReturn(btcPrice);
        when(userWalletMapper.findByUserIdAndSymbolId(1L, 1L)).thenReturn(null); // no BTC wallet

        // When / Then
        assertThatThrownBy(() -> tradeService.executeTrade(request))
                .isInstanceOf(InsufficientBalanceException.class);
    }

    @Test
    void executeTrade_Sell_ShouldThrowInsufficientBalance_WhenBtcBalanceTooLow() {
        // Given
        TradeRequest request = new TradeRequest();
        request.setUserId(1L);
        request.setPairName("BTCUSDT");
        request.setTradeType(TradeType.SELL);
        request.setQuantity(new BigDecimal("0.5")); // trying to sell 0.5 BTC

        btcWallet.setBalance(new BigDecimal("0.01")); // only 0.01 BTC
        when(cryptoPairMapper.findByPairName("BTCUSDT")).thenReturn(btcPair);
        when(cryptoPriceMapper.findLatestPriceByCryptoPairId(1L)).thenReturn(btcPrice);
        when(userWalletMapper.findByUserIdAndSymbolId(1L, 1L)).thenReturn(btcWallet);

        // When / Then
        assertThatThrownBy(() -> tradeService.executeTrade(request))
                .isInstanceOf(InsufficientBalanceException.class)
                .hasMessageContaining("BTC");
    }
}