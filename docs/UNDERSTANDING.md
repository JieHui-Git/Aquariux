# Assessment Understanding — Steps 2 to 5

---

## Step 2: Business Requirements (`1_BUSINESS_REQUIREMENTS.md`)

### What is this system?
A cryptocurrency trading platform where users can buy and sell BTC and ETH using USDT as the quote currency.

### What does it need to do?

**Trading Operations**
- Users can place BUY or SELL orders for two supported pairs: `BTCUSDT` and `ETHUSDT`
- Trades always execute at the current market price (no limit/stop orders)
- Users can only trade if they have enough balance — no credit, no overdraft
- Every trade is permanently recorded for audit purposes (records cannot be deleted or modified)

**Wallet Management**
- Each user has a wallet per currency they hold (BTC, ETH, USDT)
- Wallets are created only when a user first acquires that currency (not upfront)
- Balances update immediately after every trade

**Price Discovery**
- Prices are fetched from two external exchanges: **Binance** and **Huobi**
- The system picks the **best price** across both sources:
  - Best BID = highest bid (most a buyer will pay → best for sellers)
  - Best ASK = lowest ask (least a seller will accept → best for buyers)
- Prices are refreshed on a schedule (every 10 seconds via a scheduler)

**User Management**
- Users are identified by a `userId` — no login/auth needed for this assessment
- Assume all incoming requests are already authenticated

### What is OUT of scope?
- No transaction fees
- No limit/stop-loss orders
- No trade cancellation
- No deposits or withdrawals
- No user registration or login

### Key constraints (production requirements)
- API responses must be under 200ms
- Zero balance calculation errors — financial precision is critical
- All trades must be timestamped and immutable once created
- Must handle at least 100 simultaneous users

---

## Step 3: Database Schema (`2_DATABASE_SCHEMA.md`)

### The 6 tables and what they do

| Table | Purpose |
|-------|---------|
| `users` | Stores user accounts (id, username, email, password) |
| `symbols` | The individual currencies: BTC, ETH, USDT |
| `crypto_pairs` | Trading pairs formed from two symbols: BTCUSDT, ETHUSDT |
| `crypto_prices` | Historical price records — bid/ask price per pair, with the source exchange |
| `trades` | Every executed trade — permanent audit log |
| `user_wallets` | Each user's balance per currency (one row per user+symbol combo) |

### How the tables relate

```
users ──────────────────┬──── trades (a user places trades)
                        └──── user_wallets (a user owns wallets)

symbols ────────────────┬──── crypto_pairs (base currency, e.g. BTC in BTCUSDT)
                        ├──── crypto_pairs (quote currency, e.g. USDT in BTCUSDT)
                        └──── user_wallets (a wallet holds a symbol)

crypto_pairs ───────────┬──── trades (a trade happens on a pair)
                        └──── crypto_prices (a pair has many price records)
```

### Key data type decisions
- All financial values use `DECIMAL(20,8)` — 8 decimal places of precision, essential for crypto
- `user_wallets` has a UNIQUE constraint on `(user_id, symbol_id)` — one wallet row per currency per user
- `trades.trade_type` is constrained to `'BUY'` or `'SELL'` only

### What this means for the trade implementation
When a BUY executes:
1. Look up `crypto_pairs` to get the pair
2. Look up latest `crypto_prices` for that pair → use the `ask_price`
3. Deduct from user's USDT wallet in `user_wallets`
4. Add to user's base currency wallet (e.g. BTC) in `user_wallets` — create row if it doesn't exist
5. Insert a record into `trades`

When a SELL executes:
1. Same pair/price lookup → use the `bid_price`
2. Deduct from user's base currency wallet
3. Add to user's USDT wallet
4. Insert into `trades`

---

## Step 4: Application Overview (`3_APPLICATION_OVERVIEW.md`)

### Technology stack
| Technology | Version | Role |
|-----------|---------|------|
| Spring Boot | 3.5.7 | Web framework |
| Java | 21 | Language |
| MyBatis | 3.0.4 | Database access (SQL mapper) |
| H2 | in-memory | Database (resets on restart) |
| Flyway | — | DB migration (auto-runs SQL scripts on startup) |
| Spring Security | 6.x | Security config (permissive for assessment) |
| SpringDoc OpenAPI | 2.7.0 | Swagger UI auto-generated from annotations |
| JUnit 5 / Mockito | — | Unit testing |

### Project structure explained

```
config/         → App-wide settings (security, scheduler, Swagger)
controller/     → HTTP layer — receives requests, returns responses
dto/request/    → What comes IN to the API (TradeRequest)
dto/response/   → What goes OUT of the API (TradeResponse)
dto/internal/   → Objects passed between service layers internally
entity/         → Java representations of database table rows
enums/          → TradeType: BUY or SELL
mapper/         → MyBatis interfaces — each method maps to a SQL query
scheduler/      → PriceUpdateScheduler runs every 10s to fetch fresh prices
service/        → Business logic (interfaces + implementations)
db/migration/   → V1–V4 SQL scripts Flyway runs on startup
```

### The 3 API endpoints

| Method | Path | Status | Purpose |
|--------|------|--------|---------|
| `POST` | `/api/trades/execute` | **TO IMPLEMENT** | Execute a buy or sell trade |
| `GET` | `/api/wallets/user/{userId}` | Done | Get a user's wallet balances |
| `GET` | `/api/prices/latest` | Done | Get the current best prices for all pairs |

### What's already working vs. what needs to be built

**Already working:**
- Wallet balance retrieval
- Price aggregation from Binance and Huobi
- All database entities, schema, and seed data
- Swagger UI, H2 console, security config

**Needs to be implemented:**
- `POST /api/trades/execute` — the entire trade execution flow

---

## Step 5: Assessment Task (`4_ASSESSMENT.md`)

### The one thing to implement
`POST /api/trades/execute` — allow a user to buy or sell a crypto pair.

### The 4 files to work in

| File | What to do |
|------|-----------|
| `TradeController.java` | Wire up the endpoint, call the service |
| `TradeServiceImpl.java` | All the business logic (validate → price → wallet → save) |
| `TradeRequest.java` | Define what fields the caller must send |
| `TradeResponse.java` | Define what fields the API sends back |
| `TradeMapper.java` | Add SQL operations for inserting trades and updating wallets |

### What good implementation looks like
- Proper error handling (insufficient balance, invalid pair, user not found)
- Data consistency — wallet debit and trade insert must succeed or fail together (transaction)
- Financial precision — use `BigDecimal`, never `double` or `float`
- Follows the existing code patterns (MyBatis, service interfaces, DTOs)
- Meaningful HTTP status codes (200 OK, 400 Bad Request, 422 Unprocessable, etc.)
- Unit tests for `TradeServiceImpl` covering happy path and error cases

### Evaluation criteria (what they're looking for)
1. **Implementation** — Does it work correctly end-to-end?
2. **Design** — Is the code clean, well-structured, and following patterns?
3. **Problem Solving** — Were edge cases identified and handled?
4. **Professionalism** — Is it production-quality? Are assumptions documented?

---

## Summary: What needs to be built

One endpoint. Full flow:

```
POST /api/trades/execute
  Body: { userId, pairName, tradeType, quantity }

  → Validate inputs
  → Look up latest best price for the pair
  → Check user has sufficient balance
  → Debit source wallet
  → Credit destination wallet  (create wallet row if first time holding that currency)
  → Insert trade record
  → Return trade confirmation

  Response: { tradeId, pairName, tradeType, quantity, price, totalAmount, tradeTime }
```
