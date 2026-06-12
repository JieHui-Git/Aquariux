# SQL Cheatsheet

---

## Clause Order (must always follow this sequence)

```sql
SELECT
FROM
JOIN
WHERE       -- filters individual rows BEFORE grouping (cannot use aggregates or aliases)
GROUP BY
HAVING      -- filters groups AFTER aggregation (can use aggregates, cannot use aliases)
ORDER BY    -- can reference aliases defined in SELECT
LIMIT
```

---

## WHERE vs HAVING

| | WHERE | HAVING |
|---|---|---|
| Runs | Before grouping | After grouping |
| Use for | Filtering individual rows | Filtering aggregated results |
| Can use aggregates? | ❌ | ✅ |
| Can use aliases? | ❌ | ❌ |

**Example:**
```sql
-- WHERE filters rows before grouping
WHERE trade_type = 'BUY'

-- HAVING filters groups after aggregation
HAVING SUM(total_amount) > 10000
```

---

## Alias Rule

Aliases defined in SELECT **cannot** be referenced in the same SELECT, WHERE, or HAVING.
Only ORDER BY can reference aliases.

```sql
-- ❌ Wrong — alias used in HAVING
SELECT SUM(total_amount) AS total_spent
HAVING total_spent > 10000

-- ✅ Correct — repeat the full expression in HAVING
SELECT SUM(total_amount) AS total_spent
HAVING SUM(total_amount) > 10000

-- ✅ OK in ORDER BY
ORDER BY total_spent DESC
```

---

## Aggregate Functions

| Function | What it does |
|---|---|
| `COUNT(*)` | Count number of rows |
| `SUM(column)` | Add up values |
| `AVG(column)` | Average of values |
| `MAX(column)` | Highest value |
| `MIN(column)` | Lowest value |

---

## JOIN Types

```sql
-- INNER JOIN — only rows with matches on both sides
JOIN trades ON users.id = trades.user_id

-- LEFT JOIN — all rows from left table, nulls where no match on right
LEFT JOIN trades ON users.id = trades.user_id
```

**Find users with no trades (LEFT JOIN + IS NULL):**
```sql
SELECT * FROM users
LEFT JOIN trades ON users.id = trades.user_id
WHERE trades.id IS NULL;
```

Note: always use `IS NULL` not `= NULL` — `= NULL` never works in SQL.

---

## NULL Checks

```sql
-- ❌ Wrong
WHERE trades.id = NULL

-- ✅ Correct
WHERE trades.id IS NULL
WHERE trades.id IS NOT NULL
```

---

## Date Filtering

```sql
-- Trades in the last 7 days
WHERE trades.trade_time >= NOW() - INTERVAL '7 days'
```

---

## CASE WHEN (conditional logic inside aggregates)

Useful for splitting one column into multiple aggregated columns:

```sql
SUM(CASE WHEN trade_type = 'BUY' THEN total_amount ELSE 0 END) AS total_bought,
SUM(CASE WHEN trade_type = 'SELL' THEN total_amount ELSE 0 END) AS total_sold
```

---

## Subquery Pattern (latest record per group)

Used when you need the latest row per user/pair etc.

```sql
SELECT users.username, crypto_pairs.pair_name, trades.trade_type, trades.trade_time
FROM (
    SELECT user_id, MAX(trade_time) AS latest_trade_time
    FROM trades
    GROUP BY user_id
) a
JOIN trades ON trades.user_id = a.user_id
          AND trades.trade_time = a.latest_trade_time
JOIN users ON users.id = trades.user_id
JOIN crypto_pairs ON crypto_pairs.id = trades.crypto_pair_id
ORDER BY trades.trade_time DESC;
```

---

## Queries Practised

### 1. Basic SELECT with WHERE and ORDER BY
```sql
SELECT * FROM trades
WHERE trade_type = 'SELL'
ORDER BY trade_time DESC;
```

### 2. COUNT per group
```sql
SELECT user_id, COUNT(*) AS trade_count
FROM trades
GROUP BY user_id
ORDER BY trade_count DESC;
```

### 3. Users with no trades (LEFT JOIN + IS NULL)
```sql
SELECT * FROM users
LEFT JOIN trades ON users.id = trades.user_id
WHERE trades.id IS NULL;
```

### 4. AVG with HAVING
```sql
SELECT crypto_pairs.pair_name, AVG(trades.quantity) AS average_quantity
FROM trades
JOIN crypto_pairs ON trades.crypto_pair_id = crypto_pairs.id
GROUP BY crypto_pairs.pair_name
HAVING AVG(trades.quantity) > 0.05;
```

### 5. Latest trade per user (subquery)
```sql
SELECT users.username, crypto_pairs.pair_name, trades.trade_type, trades.trade_time
FROM (
    SELECT user_id, MAX(trade_time) AS latest_trade_time
    FROM trades
    GROUP BY user_id
) a
JOIN trades ON trades.user_id = a.user_id
          AND trades.trade_time = a.latest_trade_time
JOIN users ON users.id = trades.user_id
JOIN crypto_pairs ON crypto_pairs.id = trades.crypto_pair_id
ORDER BY trades.trade_time DESC;
```

### 6. SUM with HAVING (users who spent > 10,000 USDT)
```sql
SELECT users.username, SUM(total_amount) AS total_spent
FROM users
JOIN trades ON users.id = trades.user_id
WHERE trades.trade_type = 'BUY'
GROUP BY users.username
HAVING SUM(total_amount) > 10000
ORDER BY total_spent DESC;
```

### 7. Date filter (trades in last 7 days)
```sql
SELECT users.username, crypto_pairs.pair_name, trades.trade_type, trades.total_amount, trades.trade_time
FROM users
JOIN trades ON users.id = trades.user_id
JOIN crypto_pairs ON trades.crypto_pair_id = crypto_pairs.id
WHERE trades.trade_time >= NOW() - INTERVAL '7 days';
```

### 8. Three-table JOIN with WHERE
```sql
SELECT users.username, symbols.symbol, user_wallets.balance
FROM users
JOIN user_wallets ON users.id = user_wallets.user_id
JOIN symbols ON symbols.id = user_wallets.symbol_id
WHERE user_wallets.balance > 0;
```

### 9. COUNT grouped by two columns
```sql
SELECT crypto_pairs.pair_name, trades.trade_type, COUNT(*) AS trade_count
FROM crypto_pairs
JOIN trades ON crypto_pairs.id = trades.crypto_pair_id
GROUP BY crypto_pairs.pair_name, trades.trade_type;
```

### 10. CASE WHEN inside SUM (net buyers)
```sql
SELECT
    users.username,
    SUM(CASE WHEN trades.trade_type = 'BUY' THEN trades.total_amount ELSE 0 END) AS total_bought,
    SUM(CASE WHEN trades.trade_type = 'SELL' THEN trades.total_amount ELSE 0 END) AS total_sold,
    SUM(CASE WHEN trades.trade_type = 'BUY' THEN trades.total_amount ELSE 0 END) -
    SUM(CASE WHEN trades.trade_type = 'SELL' THEN trades.total_amount ELSE 0 END) AS net_spent
FROM users
JOIN trades ON users.id = trades.user_id
GROUP BY users.username
HAVING SUM(CASE WHEN trades.trade_type = 'BUY' THEN trades.total_amount ELSE 0 END) >
       SUM(CASE WHEN trades.trade_type = 'SELL' THEN trades.total_amount ELSE 0 END)
ORDER BY net_spent DESC;
```

---

## Your Schema (for reference)

**`users`** — `id, username, email, password, created_at, updated_at`

**`symbols`** — `id, symbol, name, active`
- id 1 = BTC, id 2 = ETH, id 3 = USDT

**`crypto_pairs`** — `id, base_symbol_id, quote_symbol_id, pair_name, active`

**`trades`** — `id, user_id, crypto_pair_id, trade_type, quantity, price, total_amount, trade_time`

**`user_wallets`** — `id, user_id, symbol_id, balance, updated_at`

**`crypto_prices`** — `id, crypto_pair_id, bid_price, ask_price, bid_source, ask_source, created_at`
