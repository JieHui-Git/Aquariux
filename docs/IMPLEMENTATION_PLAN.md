# Implementation Plan — Trade Execution System

This document explains what needs to be built, why each decision was made, and which source document each requirement comes from.

For the full list of design decisions and assumptions, see [decisions/DECISIONS.md](../decisions/DECISIONS.md).

---

## What We Are Building

A single API endpoint:

```
POST /api/trades/execute
```

This allows a user to buy or sell a cryptocurrency pair (BTCUSDT or ETHUSDT) at the current best market price, deducting from and crediting to their wallet balances, and permanently recording the trade.

**Source**: [4_ASSESSMENT.md](../4_ASSESSMENT.md) — "Complete the POST /api/trades/execute endpoint to handle cryptocurrency trading."

---

## Step 1: Define the Request (TradeRequest DTO)

**File**: `src/main/java/.../dto/request/TradeRequest.java`

**Fields added**:
| Field | Type | Why |
|-------|------|-----|
| `userId` | `Long` | Already in skeleton. Identifies who is trading. |
| `pairName` | `String` | Which pair to trade, e.g. `"BTCUSDT"`. Needed because the system supports multiple pairs. |
| `tradeType` | `TradeType` | Already in skeleton. `BUY` or `SELL`. |
| `quantity` | `BigDecimal` | How much of the base currency to buy or sell. Without this, we cannot calculate cost or deduct balances. |

**Source for pairName + quantity**:
- [1_BUSINESS_REQUIREMENTS.md](../1_BUSINESS_REQUIREMENTS.md): "Current Supported Trading Pairs: BTCUSDT, ETHUSDT" — confirms pairName is needed to distinguish between pairs.
- [2_DATABASE_SCHEMA.md](../2_DATABASE_SCHEMA.md): The `trades` table has `quantity`, `price`, and `total_amount` columns — all require a quantity input from the caller.
- [4_ASSESSMENT.md](../4_ASSESSMENT.md): The tip `{"userId": 1, "tradeType": "BUY"}` is the *starting point*, not the final design. It intentionally leaves pairName and quantity for you to figure out.

**Assumption**: Quantity is always in units of the base currency (BTC for BTCUSDT, ETH for ETHUSDT). The cost/proceeds are in USDT, calculated as `quantity × price`.

---

## Step 2: Define the Response (TradeResponse DTO)

**File**: `src/main/java/.../dto/response/TradeResponse.java`

**Fields**:
| Field | Type | Why |
|-------|------|-----|
| `tradeId` | `Long` | The generated ID of the trade record — confirms the trade was saved. |
| `userId` | `Long` | Who executed the trade. Echoed back for audit consistency — the `trades` table stores this, and including it means the response is a self-contained audit record. |
| `pairName` | `String` | Echoes back which pair was traded. |
| `tradeType` | `String` | Echoes back BUY or SELL. |
| `quantity` | `BigDecimal` | How much was traded. |
| `price` | `BigDecimal` | The exact price the trade was executed at. |
| `totalAmount` | `BigDecimal` | The total USDT cost (BUY) or proceeds (SELL). |
| `tradeTime` | `LocalDateTime` | When the trade happened. |

**Source**:
- [1_BUSINESS_REQUIREMENTS.md](../1_BUSINESS_REQUIREMENTS.md): "All trades timestamped with precise execution time" → `tradeTime`.
- [2_DATABASE_SCHEMA.md](../2_DATABASE_SCHEMA.md): The `trades` table columns map directly to these response fields.
- Convention: Modelled after the existing `BestPriceResponse.java` — flat `@Data` class, same field naming style.

---

## Step 3: Define the Database Operations (Mappers)

Each mapper is responsible only for its own table. This follows the single responsibility principle and matches the existing codebase structure.

### TradeMapper — `src/main/java/.../mapper/TradeMapper.java`

Only one method. The `trades` table is append-only by design.

| Method | SQL Operation | Why It Is Needed |
|--------|--------------|-----------------|
| `insertTrade(trade)` | INSERT into `trades` with `useGeneratedKeys` | Permanently record the trade and retrieve its generated ID for the response |

**Source**: [1_BUSINESS_REQUIREMENTS.md](../1_BUSINESS_REQUIREMENTS.md) — "Transaction records must be immutable once created." No UPDATE or DELETE is needed or allowed on this table.

---

### CryptoPairMapper — `src/main/java/.../mapper/CryptoPairMapper.java`

One method added to the existing mapper.

| Method | SQL Operation | Why It Is Needed |
|--------|--------------|-----------------|
| `findByPairName(pairName)` | SELECT from `crypto_pairs` returning full entity | Returns `baseSymbolId` and `quoteSymbolId` — the IDs of the two currencies involved in the trade, used for all wallet lookups |

**Why return the full entity instead of just the ID**: The `CryptoPair` entity carries `baseSymbolId` and `quoteSymbolId` directly. Using these IDs for wallet lookups avoids any string manipulation (e.g. stripping "USDT" from the pair name to derive the base currency). See [decisions/DECISIONS.md](../decisions/DECISIONS.md) Decision #7.

---

### CryptoPriceMapper — `src/main/java/.../mapper/CryptoPriceMapper.java`

One method added to the existing mapper.

| Method | SQL Operation | Why It Is Needed |
|--------|--------------|-----------------|
| `findLatestPriceByCryptoPairId(cryptoPairId)` | SELECT from `crypto_prices`, MAX timestamp subquery, filtered by `crypto_pair_id` | Get the single most recent bid/ask price for the pair to execute the trade at |

**Why no JOIN**: By the time this is called, the service already has `cryptoPairId` from the `CryptoPair` lookup in the previous step. No join to `crypto_pairs` is needed.

**Convention**: Uses the same `WHERE created_at = (SELECT MAX(created_at)...)` subquery pattern as the existing `findLatestPrices()` — just filtered to one pair by ID instead of returning all pairs.

**Source**: [1_BUSINESS_REQUIREMENTS.md](../1_BUSINESS_REQUIREMENTS.md) — "All trades must be executed at current market prices."

---

### UserWalletMapper — `src/main/java/.../mapper/UserWalletMapper.java`

Three methods added to the existing mapper.

| Method | SQL Operation | Why It Is Needed |
|--------|--------------|-----------------|
| `findByUserIdAndSymbolId(userId, symbolId)` | SELECT from `user_wallets` JOIN `symbols`, filtered by user and symbol ID | Check if the user has a wallet for a given currency and what the current balance is |
| `updateBalance(walletId, balance)` | UPDATE `user_wallets` SET balance | Deduct from the spend wallet or credit the receive wallet after a trade |
| `insertWallet(userId, symbolId, balance)` | INSERT into `user_wallets` | Create a wallet row the first time a user acquires a currency |

**Why look up by symbolId, not symbol name**: The `CryptoPair` entity already provides `baseSymbolId` and `quoteSymbolId`. Using these IDs directly avoids an extra query to resolve a symbol name to its ID.

**Source**:
- [1_BUSINESS_REQUIREMENTS.md](../1_BUSINESS_REQUIREMENTS.md): "Wallet balances must be updated in real-time after trades" → `updateBalance`.
- [1_BUSINESS_REQUIREMENTS.md](../1_BUSINESS_REQUIREMENTS.md): "Wallet records are created only when users first acquire a cryptocurrency" → `insertWallet`.
- [2_DATABASE_SCHEMA.md](../2_DATABASE_SCHEMA.md): `user_wallets` has a UNIQUE constraint on `(user_id, symbol_id)` — one wallet row per user per currency.

---

## Step 4: Handle Errors (Exceptions + GlobalExceptionHandler)

**Files created**:
- `exception/InsufficientBalanceException.java`
- `exception/InvalidTradePairException.java`
- `exception/PriceNotFoundException.java`
- `exception/GlobalExceptionHandler.java`
- `dto/response/ErrorResponse.java`

**Why each exception exists**:

| Exception | When It Is Thrown | HTTP Status |
|-----------|------------------|-------------|
| `InvalidTradePairException` | `pairName` not found in `crypto_pairs`, or pair is inactive | 400 Bad Request |
| `PriceNotFoundException` | No price record exists for the requested pair | 422 Unprocessable Entity |
| `InsufficientBalanceException` | User's wallet balance is less than required | 422 Unprocessable Entity |
| `IllegalArgumentException` | Required fields are null, or quantity ≤ 0 | 400 Bad Request |

**Source**:
- [1_BUSINESS_REQUIREMENTS.md](../1_BUSINESS_REQUIREMENTS.md): "Users can only trade with available wallet balance" → InsufficientBalanceException.
- [4_ASSESSMENT.md](../4_ASSESSMENT.md): "Implement proper error handling" and "Consider what makes an API reliable in production".
- [3_APPLICATION_OVERVIEW.md](../3_APPLICATION_OVERVIEW.md): "API response success rate > 99.5% / Zero balance calculation errors" — errors must be returned cleanly, not bubble up as 500s.

**Assumption**: 422 (Unprocessable Entity) is more accurate than 400 for business rule violations — the request is well-formed JSON, but the operation cannot proceed due to domain constraints. See [decisions/DECISIONS.md](../decisions/DECISIONS.md) Decision #5.

**Security config fix**: `/error` was added to `SecurityConfig.DEVELOPMENT_ENDPOINTS`. Spring Boot forwards unhandled exceptions to `/error` internally — without this, Spring Security intercepted that forward and returned 403, masking the real error.

---

## Step 5: Implement the Core Logic (TradeServiceImpl)

**File**: `src/main/java/.../service/impl/TradeServiceImpl.java`

The full execution flow inside `executeTrade` (the only `@Transactional` method):

```
1. Validate inputs — userId, pairName, tradeType, quantity not null; quantity > 0
2. Look up the crypto pair by pairName → get baseSymbolId, quoteSymbolId, active flag
   → throws InvalidTradePairException if not found or inactive
3. Get the latest aggregated price for that pair by cryptoPairId
   → throws PriceNotFoundException if none exists
4. Select execution price:
     BUY  → use ask_price (what sellers are asking — the price the buyer pays)
     SELL → use bid_price (what buyers are bidding — the price the seller receives)
5. Calculate totalAmount = quantity × executionPrice
6. Execute wallet updates:
     BUY:
       a. Find the user's USDT wallet (quoteSymbolId)
       b. Check balance ≥ totalAmount → throws InsufficientBalanceException if not
       c. Deduct totalAmount from USDT wallet
       d. Find the user's base currency wallet (baseSymbolId)
       e. If wallet exists → credit quantity; if not → insertWallet with quantity as opening balance
     SELL:
       a. Find the user's base currency wallet (baseSymbolId)
       b. Check balance ≥ quantity → throws InsufficientBalanceException if not
       c. Deduct quantity from base currency wallet
       d. Find the user's USDT wallet (quoteSymbolId)
       e. If wallet exists → credit totalAmount; if not → insertWallet with totalAmount as opening balance
7. Insert the trade record → generated ID is written back into the entity via @Options(useGeneratedKeys=true)
8. Build and return TradeResponse
```

**All steps run within a single `@Transactional` boundary on `executeTrade`.** The three private helpers (`validateRequest`, `executeBuy`, `executeSell`) participate in the same transaction automatically — Spring's proxy only wraps public methods, so `@Transactional` on a private method would be silently ignored.

**Source for each step**:

| Step | Source |
|------|--------|
| Validate inputs | [4_ASSESSMENT.md](../4_ASSESSMENT.md): "Implement proper error handling / Ensure data consistency" |
| Pair lookup + active check | [2_DATABASE_SCHEMA.md](../2_DATABASE_SCHEMA.md): `crypto_pairs` table, `active` column |
| Price lookup | [1_BUSINESS_REQUIREMENTS.md](../1_BUSINESS_REQUIREMENTS.md): "All trades must be executed at current market prices" |
| BUY uses ask_price | [1_BUSINESS_REQUIREMENTS.md](../1_BUSINESS_REQUIREMENTS.md): "Best price selection (lowest ask, highest bid)" — lowest ask is what you pay when buying |
| SELL uses bid_price | Same — highest bid is what you receive when selling |
| Balance check | [1_BUSINESS_REQUIREMENTS.md](../1_BUSINESS_REQUIREMENTS.md): "Users can only trade with available wallet balance" |
| Wallet debit/credit | [1_BUSINESS_REQUIREMENTS.md](../1_BUSINESS_REQUIREMENTS.md): "Wallet balances must be updated in real-time after trades" |
| New wallet on first acquisition | [1_BUSINESS_REQUIREMENTS.md](../1_BUSINESS_REQUIREMENTS.md): "Wallet records are created only when users first acquire a cryptocurrency" |
| Trade insert | [1_BUSINESS_REQUIREMENTS.md](../1_BUSINESS_REQUIREMENTS.md): "Transaction records must be immutable once created" |
| @Transactional | [4_ASSESSMENT.md](../4_ASSESSMENT.md): "Ensure data consistency" — if any step fails, all changes roll back |

**Assumption — no transaction fees**: `totalAmount = quantity × price` with no fee deducted. The assessment does not specify any fee structure. See [decisions/DECISIONS.md](../decisions/DECISIONS.md) Decision #12.

**Assumption — BigDecimal for all math**: Never `double` or `float`. See [decisions/DECISIONS.md](../decisions/DECISIONS.md) Decision #6.

---

## Step 6: Write Unit Tests (TradeServiceImplTest)

**File**: `src/test/java/.../service/impl/TradeServiceImplTest.java`

Follows the exact same pattern as `WalletServiceImplTest.java` and `PriceServiceImplTest.java` — Mockito mocks for all mappers, `@InjectMocks` for the service, `@ExtendWith(MockitoExtension.class)`.

**Test cases**:

| Test | What It Verifies |
|------|-----------------|
| BUY — both wallets exist | Ask price used, USDT deducted, BTC credited via `updateBalance`, trade inserted, response correct |
| BUY — no BTC wallet yet | USDT deducted, BTC wallet created via `insertWallet` with quantity as opening balance |
| SELL — both wallets exist | Bid price used, BTC deducted, USDT credited via `updateBalance`, trade inserted |
| SELL — no USDT wallet yet | BTC deducted, USDT wallet created via `insertWallet` with proceeds as opening balance |
| Null userId | `IllegalArgumentException` before any DB call |
| Zero quantity | `IllegalArgumentException` before any DB call |
| Negative quantity | `IllegalArgumentException` before any DB call |
| Unknown pair name | `InvalidTradePairException` |
| Inactive pair | `InvalidTradePairException` |
| No price available | `PriceNotFoundException` |
| BUY — no USDT wallet | `InsufficientBalanceException` mentioning USDT |
| BUY — USDT balance too low | `InsufficientBalanceException` mentioning USDT |
| SELL — no BTC wallet | `InsufficientBalanceException` |
| SELL — BTC balance too low | `InsufficientBalanceException` mentioning BTC |

**Source**: [4_ASSESSMENT.md](../4_ASSESSMENT.md): "TradeServiceImplTest.java (to be implemented)" is explicitly listed as a deliverable.

---

## Step 7: Bug Fixes (PriceUpdateScheduler)

**File**: `src/main/java/.../scheduler/PriceUpdateScheduler.java`

Three bugs were found and fixed:

| Bug | Location | Fix |
|-----|----------|-----|
| Bid and ask prices stored in wrong fields | `storePriceHistory` | Swapped back to `setBidPrice(bidPrice)` and `setAskPrice(askPrice)` |
| Bid and ask sources stored in wrong fields | `storePriceHistory` | Swapped back to `setBidSource(bidSource)` and `setAskSource(askSource)` |
| Pair name lookup inverted | `getCryptoPairId` | Removed the swap — now looks up `pairName` directly |

**Assumption**: These are clear bugs — not intentional test traps. Leaving them in would cause all prices stored in the DB to be wrong, breaking the trade execution logic. See [decisions/DECISIONS.md](../decisions/DECISIONS.md) Decision #10.

---

## Files Changed / Created Summary

| File | Action | Purpose |
|------|--------|---------|
| `dto/request/TradeRequest.java` | Modified | Added `pairName`, `quantity` |
| `dto/response/TradeResponse.java` | Modified | Added all response fields including `userId` |
| `dto/response/ErrorResponse.java` | Created | Structured error response body |
| `mapper/TradeMapper.java` | Modified | `insertTrade` only — trades table is immutable (no select) |
| `mapper/CryptoPairMapper.java` | Modified | Added `findByPairName` returning full `CryptoPair` entity |
| `mapper/CryptoPriceMapper.java` | Modified | Added `findLatestPriceByCryptoPairId` — no join, uses pair ID directly |
| `mapper/UserWalletMapper.java` | Modified | Added `findByUserIdAndSymbolId`, `updateBalance`, `insertWallet` |
| `exception/InsufficientBalanceException.java` | Created | Business rule violation — not enough balance |
| `exception/InvalidTradePairException.java` | Created | Unknown or inactive trading pair |
| `exception/PriceNotFoundException.java` | Created | No price data available for pair |
| `exception/GlobalExceptionHandler.java` | Created | Maps exceptions to structured HTTP responses |
| `service/impl/TradeServiceImpl.java` | Modified | Full trade execution logic |
| `controller/TradeController.java` | Modified | Wired `executeTrade` — removed TODO stub |
| `config/SecurityConfig.java` | Modified | Added `/error` to permitted paths — fixes 403 masking real errors |
| `scheduler/PriceUpdateScheduler.java` | Modified | Fixed 3 bugs — swapped prices/sources and inverted pair lookup |
| `test/.../TradeServiceImplTest.java` | Created | 12 unit tests covering all happy paths and error cases |
| `decisions/DECISIONS.md` | Created | All 12 design decisions documented with sources |
| `docs/UNDERSTANDING.md` | Created | Explanation of all 4 source documents |
| `docs/IMPLEMENTATION_PLAN.md` | Created | This file |