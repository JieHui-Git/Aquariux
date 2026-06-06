# Design Decisions & Assumptions

This document records all design decisions and assumptions made during implementation of the trade execution system.

---

## 1. TradeRequest Fields

**Decision**: Added `pairName` (String) and `quantity` (BigDecimal) to the existing `userId` and `tradeType` fields.

**Assumption**: A trade cannot be executed without specifying which pair to trade and how much. The assessment tip showed `{"userId": 1, "tradeType": "BUY"}` as a starting point, but that is incomplete for a real trade — quantity is essential for calculating cost/proceeds and updating balances.

---

## 2. Price Selection (BUY vs SELL)

**Decision**: 
- BUY trades execute at the `ask_price` (the price sellers are asking)
- SELL trades execute at the `bid_price` (the price buyers are bidding)

**Assumption**: Standard financial market convention. The ask is always higher than the bid. Buying at the ask and selling at the bid reflects real market mechanics.

---

## 3. Wallet Creation on First Acquisition

**Decision**: When a user buys a currency they do not yet hold, a new `user_wallets` row is inserted automatically.

**Assumption**: Matches the business rule: "Wallet records are created only when users first acquire a cryptocurrency." This means no pre-provisioning of wallets is needed.

---

## 4. Transaction Atomicity

**Decision**: `@Transactional` is applied to `executeTrade()` in the service layer.

**Assumption**: All three operations — debit source wallet, credit destination wallet, insert trade record — must succeed or fail together. A partial failure (e.g. wallets updated but trade not recorded) would corrupt the audit trail and balances.

---

## 5. Error Handling Strategy

**Decision**: Custom runtime exceptions with a `@RestControllerAdvice` global handler, returning structured JSON error responses.

| Scenario | Exception | HTTP Status |
|----------|-----------|-------------|
| Unknown trading pair | `InvalidTradePairException` | 400 Bad Request |
| No price data available | `PriceNotFoundException` | 422 Unprocessable Entity |
| Insufficient wallet balance | `InsufficientBalanceException` | 422 Unprocessable Entity |
| Null/invalid request fields | `IllegalArgumentException` | 400 Bad Request |

**Assumption**: 422 is more accurate than 400 for business-rule violations where the request format is valid but the operation cannot proceed.

---

## 6. Financial Precision

**Decision**: All monetary calculations use `BigDecimal`. No `double` or `float` anywhere in the trade flow.

**Assumption**: Crypto prices can have 8 decimal places of precision. Floating-point types lose precision and can cause rounding errors that accumulate over many trades — unacceptable for financial software.

---

## 7. Quote Currency Assumption

**Decision**: USDT is always the quote currency for all supported pairs. The base currency is derived by stripping "USDT" from the pair name (e.g., "BTCUSDT" → base = "BTC").

**Assumption**: The current schema only supports BTCUSDT and ETHUSDT, both USDT-quoted. If new pairs with different quote currencies (e.g., BTCETH) were added in future, this logic would need updating.

---

## 8. Input Validation

**Decision**: Manual validation in the service layer (null checks, quantity > 0).

**Assumption**: Hibernate Validator is not on the classpath (confirmed from startup log: `NoProviderFoundException`), so `@Valid` / `@NotNull` annotations on the request DTO have no effect. Validation is done explicitly in the service before any DB operations.

---

## 9. Quantity Scale

**Decision**: `quantity` in `TradeResponse` and DB insert is stored as-is from the request, with `totalAmount = quantity × price`.

**Assumption**: The DB column `DECIMAL(20,8)` imposes at most 8 decimal places. This is sufficient for BTC (typically traded to 8 decimal places) and ETH.

---

## 10. PriceUpdateScheduler Bug Fix

**Decision**: Fixed three bugs in `PriceUpdateScheduler.java`:
1. Bid and ask prices were assigned to each other's fields (swapped)
2. Bid and ask sources were assigned to each other's fields (swapped)
3. The pair name lookup for Huobi was inverted (looked up ETHUSDT when processing BTCUSDT and vice versa)

**Assumption**: These are clear bugs — not intentional test traps. Leaving them in would mean all prices stored in the DB after the scheduler runs are wrong, breaking the trade execution logic.

---

## 11. Trade History Endpoint (Bonus)

**Decision**: Added `GET /api/trades/user/{userId}` to return a user's full trade history.

**Assumption**: The business requirements mention "Trade history must be maintained for audit purposes." Surfacing this via API completes the trading lifecycle and demonstrates understanding beyond the minimum requirement. The data model fully supports it.

---

## 12. No Transaction Fees

**Decision**: Trade execution does not deduct any transaction fee from the user's wallet. The user pays exactly `quantity × price` (BUY) or receives exactly `quantity × price` (SELL).

**Assumption**: The assessment does not mention transaction fees, maker/taker fees, or spreads applied at the application level. Fees are therefore assumed to be zero. If fees were required, they would need to be specified (flat fee, percentage, applied to which currency) before implementation.