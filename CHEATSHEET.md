# Aquariux Interview Cheatsheet

---

## 1. `@Transactional` + Self-Invocation Trap

**What it does:**
`@Transactional` on `executeTrade()` ensures all 3 DB operations — debit source wallet, credit destination wallet, insert trade record — succeed or fail together. If any step fails, the entire transaction rolls back. No money lost without a trace.

**How it works:**
Spring wraps the method in a AOP proxy. The proxy opens a DB transaction before the method runs, and commits or rolls back after.

**Self-invocation trap:**
If a method inside the same class calls another `@Transactional` method directly (`this.method()`), it bypasses the Spring proxy — no transaction is opened. Solution: always call `@Transactional` methods from a different class (e.g. controller calls service).

```java
// Correct — called from controller, proxy intercepts it
tradeService.executeTrade(request);

// Wrong — self-invocation, proxy bypassed, no transaction
this.executeTrade(request);
```

---

## 2. 3 Bugs in `PriceUpdateScheduler`

**Bug 1 — Bid/ask prices swapped:**
```java
// Buggy
cryptoPrice.setBidPrice(askPrice);
cryptoPrice.setAskPrice(bidPrice);

// Fixed
cryptoPrice.setBidPrice(bidPrice);
cryptoPrice.setAskPrice(askPrice);
```

**Bug 2 — Bid/ask sources swapped:**
```java
// Buggy
cryptoPrice.setBidSource(askSource);
cryptoPrice.setAskSource(bidSource);

// Fixed
cryptoPrice.setBidSource(bidSource);
cryptoPrice.setAskSource(askSource);
```

**Bug 3 — Huobi pair lookup inverted:**
```java
// Buggy — looked up the wrong pair name
return cryptoPairMapper.findIdByPairName(
    pairName.equals("BTCUSDT") ? "ETHUSDT" : "BTCUSDT"
);

// Fixed
return cryptoPairMapper.findIdByPairName(pairName);
```

---

## 3. Price Aggregation Flow

Scheduler runs every 10 seconds, fetches prices from Binance and Huobi, selects the best bid (highest) and best ask (lowest) across both exchanges, and stores one row per pair in `crypto_prices`.

**Why highest bid / lowest ask?**
- Highest bid = best price to sell your coins (most USDT back)
- Lowest ask = best price to buy coins (least USDT spent)

**Sentinel initialisation:**
```java
BigDecimal bestBidPrice = BigDecimal.ZERO;              // any real bid will beat 0
BigDecimal bestAskPrice = BigDecimal.valueOf(Double.MAX_VALUE); // any real ask will beat MAX
```
This is a classic running max/min pattern — same as finding the max value in an array.

---

## 4. SOLID Principles (mapped to actual code)

**S — Single Responsibility**
Each class has one job:
- `TradeController` — HTTP concerns only (request mapping, response codes)
- `TradeServiceImpl` — trade execution logic only
- `GlobalExceptionHandler` — exception-to-response mapping only
- Each mapper (`TradeMapper`, `UserWalletMapper` etc.) — queries for its own table only

**O — Open/Closed**
Open for extension, closed for modification. Add a new error type by adding a new exception class and a new handler — no existing code touched:
```java
// New exception
public class InsufficientBalanceException extends RuntimeException { ... }

// New handler in GlobalExceptionHandler — existing handlers untouched
@ExceptionHandler(InsufficientBalanceException.class)
public ResponseEntity<ErrorResponse> handleInsufficientBalance(...) { ... }
```

**L — Liskov Substitution**
`TradeController` depends on `TradeServiceInterface`. Any class implementing that interface can be swapped in — a mock in tests, a new implementation in production — and `TradeController` works without any changes.

**I — Interface Segregation**
Mapper interfaces are split by entity. `TradeServiceImpl` only injects the mappers it actually needs — no class is forced to depend on methods it doesn't use:
```java
private final TradeMapper tradeMapper;
private final UserWalletMapper userWalletMapper;
private final CryptoPairMapper cryptoPairMapper;
private final CryptoPriceMapper cryptoPriceMapper;
```

**D — Dependency Inversion**
`TradeController` depends on the abstraction, not the concrete class. Spring injects the implementation at runtime:
```java
import com.aquariux.technical.assessment.trade.service.TradeServiceInterface; // interface, not impl

@RequiredArgsConstructor
public class TradeController {
    private final TradeServiceInterface tradeService; // Spring injects TradeServiceImpl at runtime
}
```

---

## 5. Concurrent Trade Handling + Locking

**The problem:**
Two requests for the same user arrive simultaneously. Both read balance = 1000 USDT. Both calculate they can afford a 600 USDT trade. Both write — result: balance goes to -200. User gets coins they can't afford.

**Pessimistic Locking — `SELECT FOR UPDATE`:**
Locks the row at read time. Thread 2 is blocked until Thread 1 commits.
```xml
<select id="getWalletForUpdate" resultType="UserWallet">
    SELECT * FROM user_wallets
    WHERE user_id = #{userId} AND symbol_id = #{symbolId}
    FOR UPDATE
</select>
```
- Pro: simple, no conflicts possible
- Con: threads queue up under high load → latency increases

**Optimistic Locking — version column:**
No locks. Each row has a version number. Update only succeeds if version matches what you read.
```xml
<update id="updateWalletBalance">
    UPDATE user_wallets
    SET balance = #{newBalance}, version = version + 1
    WHERE user_id = #{userId}
    AND symbol_id = #{symbolId}
    AND version = #{expectedVersion}
</update>
```
If 0 rows updated → conflict detected → retry from the top.
- Pro: no blocking, high throughput
- Con: retry logic needed in code

**Best solution at scale: Kafka partitioned by userId**
All trades for the same user go to the same Kafka partition, processed sequentially by one consumer. No concurrent writes for the same user — eliminates the need for locking entirely.

---

## 6. Query Optimisation + Indexes

**The problem:**
`crypto_prices` gets 2 new rows every 10 seconds. After 1 year ≈ 6 million rows. Every trade needs the latest price — without an index, the DB does a full table scan of 6 million rows every time.

**The fix — composite index:**
```sql
CREATE INDEX idx_crypto_prices_pair_created
ON crypto_prices (crypto_pair_id, created_at DESC);
```
- Groups rows by `crypto_pair_id` first — skips unrelated pairs
- Within each pair, pre-sorted by `created_at DESC` — latest price is always first
- Query goes from scanning 6M rows to reading 2-3 index entries

**The query:**
```sql
SELECT * FROM crypto_prices
WHERE crypto_pair_id = #{cryptoPairId}
ORDER BY created_at DESC
LIMIT 1;
```

**Verify with EXPLAIN ANALYZE (PostgreSQL):**
```sql
EXPLAIN ANALYZE
SELECT * FROM crypto_prices
WHERE crypto_pair_id = 1
ORDER BY created_at DESC
LIMIT 1;
```
- Without index: `Seq Scan` — full table scan
- With index: `Index Scan` — fast lookup

**Trade-off:** indexes speed up reads but slow down writes slightly — every INSERT also updates the index. Worth it here since reads far outnumber writes.

---

## 7. Redis Caching

**What Redis is:**
An in-memory key-value store. Data lives in RAM — reads take microseconds vs milliseconds for a DB. Used as a caching layer to reduce DB load.

**Cache-aside pattern (lazy loading):**
1. Request comes in → check Redis first
2. Cache hit → return cached value instantly, no DB call
3. Cache miss → hit DB, store result in Redis, return value
4. Next request → cache hit

**Spring Boot annotations:**
```java
// On price lookup — serve from cache if available
@Cacheable(value = "latestPrices", key = "#cryptoPairId")
public CryptoPrice getLatestPrice(Long cryptoPairId) {
    return cryptoPriceMapper.findLatestByCryptoPairId(cryptoPairId);
}

// In scheduler — always updates DB and cache simultaneously
@CachePut(value = "latestPrices", key = "#cryptoPairId")
public CryptoPrice storePriceHistory(...) {
    cryptoPriceMapper.insertPrice(cryptoPrice);
    return newCryptoPrice;
}

// Cache invalidation — deletes entry, next read hits DB
@CacheEvict(value = "latestPrices", key = "#cryptoPairId")
public void invalidatePrice(Long cryptoPairId) { ... }
```

| Annotation | Hits DB? | Use when... |
|---|---|---|
| `@Cacheable` | Only on miss | Reading — serve from cache if possible |
| `@CachePut` | Always | Writing — keep cache in sync with DB |
| `@CacheEvict` | Own logic | Invalidating stale data |

**TTL of 10s** — matches the scheduler interval. Worst case: price is 10s stale. Using `@CachePut` in the scheduler eliminates this — cache is updated immediately after each scheduler run.

**application.yml config:**
```yaml
spring:
  redis:
    host: localhost
    port: 6379
  cache:
    type: redis
    redis:
      time-to-live: 10000  # 10s in milliseconds
```

---

## 8. REST API Best Practices

**1. Use nouns for URLs, not verbs — HTTP method IS the verb:**
```
✅ POST /api/trades/execute
❌ POST /api/executeTrade
```

**2. HTTP methods:**
| Method | Purpose |
|---|---|
| GET | Retrieve data |
| POST | Create new resource |
| PUT | Replace entire resource |
| PATCH | Partial update (specific fields only) |
| DELETE | Delete resource |

**3. HTTP status codes:**
```
200 OK                  — successful GET
201 Created             — successful POST that created a resource
400 Bad Request         — malformed input (missing fields, wrong types)
401 Unauthorized        — not authenticated (not logged in)
403 Forbidden           — authenticated but not authorised
404 Not Found           — resource doesn't exist
422 Unprocessable       — valid format but business rule failed
500 Internal Server Error — unexpected server error
```

**400 vs 422:**
- 400 — "I don't understand your request" (null field, wrong type)
- 422 — "I understand it perfectly, but I can't do it" (insufficient balance, price not found)

**4. Structured error responses:**
```json
{
  "status": 422,
  "error": "Insufficient Balance",
  "message": "User does not have enough USDT to complete this trade"
}
```

**5. Validate input early** — reject at the boundary before business logic.

**6. Use plural nouns for collections:**
```
✅ /api/trades
✅ /api/wallets/user/1
```

**7. Version your API:**
```
/api/v1/trades/execute
```

---

## 9. Error Handling Strategy (`@RestControllerAdvice`)

**What it does:**
Centralises all exception handling in one class. Instead of try/catch in every controller, Spring intercepts exceptions thrown anywhere and maps them to HTTP responses automatically.

**Without it — messy:**
```java
@PostMapping("/execute")
public ResponseEntity<?> executeTrade(@RequestBody TradeRequest request) {
    try {
        return ResponseEntity.ok(tradeService.executeTrade(request));
    } catch (InsufficientBalanceException e) {
        return ResponseEntity.status(422).body(...);
    } catch (PriceNotFoundException e) {
        return ResponseEntity.status(422).body(...);
    }
}
```

**With it — clean:**
```java
// Controller
@PostMapping("/execute")
public ResponseEntity<TradeResponse> executeTrade(@RequestBody TradeRequest request) {
    return ResponseEntity.ok(tradeService.executeTrade(request));
}

// GlobalExceptionHandler
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(InsufficientBalanceException.class)
    public ResponseEntity<ErrorResponse> handleInsufficientBalance(InsufficientBalanceException ex) {
        return ResponseEntity.status(422)
            .body(new ErrorResponse(422, "Insufficient Balance", ex.getMessage()));
    }
}
```

**Full flow when exception is thrown:**
```
TradeServiceImpl.executeTrade()
        ↓
throw new InsufficientBalanceException(...)
        ↓
Exception bubbles up through TradeController (no try/catch)
        ↓
Spring intercepts → finds @ExceptionHandler in GlobalExceptionHandler
        ↓
HTTP 422 JSON response sent to client
```

This also follows Open/Closed — add a new exception type = add one handler method, touch nothing else.

---

## 10. JWT / OAuth2

**What JWT is:**
JSON Web Token — used to prove identity on every API request. Has 3 parts separated by dots: `header.payload.signature`

- **Header** — algorithm used e.g. `HS256`
- **Payload** — claims: `userId`, `role`, `exp` (expiry), `iat` (issued at). Base64 encoded — not encrypted, don't store passwords here.
- **Signature** — cryptographic proof the token hasn't been tampered with. Generated using a secret key on the server.

**Access token vs refresh token:**
| | Access Token | Refresh Token |
|---|---|---|
| Lifespan | Short (~15 min) | Long (~7 days) |
| Sent with | Every API request | Only to /auth/refresh |
| Purpose | Prove identity | Get new access token |

**How it works in practice:**
```
User logs in → receives access token (15min) + refresh token (7 days)
        ↓
Every request: Authorization: Bearer <access_token>
        ↓
Access token expires after 15min
        ↓
Client sends refresh token → server issues new access token
        ↓
After 7 days: refresh token expires → user must log in again
```

**Refresh token rotation (more secure):**
Every time you use the refresh token, you get a brand new refresh token + new access token. Old refresh token is invalidated immediately. If stolen and used, the legitimate user's token stops working — detectable.

**Authentication vs Authorisation:**
- **Authentication** — who are you? (verify identity via login)
- **Authorisation** — what are you allowed to do? (check role/permissions)
- **401** — not authenticated ("show me your ID")
- **403** — not authorised ("I know you, but you can't come in")

**Why it matters for this project:**
Currently `userId` comes from the request body — anyone can trade on anyone's account. With JWT, `userId` would come from token claims server-side — impossible to spoof.

---

## 11. Kafka / Message Queues

**What Kafka is:**
A distributed message queue. Producers publish messages to topics, consumers read and process them. Enables asynchronous processing — the producer doesn't wait for the consumer to finish.

**Key terms:**
| Term | Meaning |
|---|---|
| Producer | Sends messages to a topic |
| Consumer | Reads and processes messages |
| Topic | Named channel (e.g. `trade-requests`) |
| Partition | Ordered sub-division of a topic |
| Offset | Position of a message within a partition |
| Consumer Group | Multiple consumers sharing work of a topic |

**Why partition by userId for trades:**
```
Topic: trade-requests
  ├── Partition 0 — userId 1, 4, 7... (one consumer, sequential)
  ├── Partition 1 — userId 2, 5, 8... (one consumer, sequential)
  └── Partition 2 — userId 3, 6, 9... (one consumer, sequential)
```
All trades for the same user land in the same partition, processed in order by one consumer. No two trades for the same user run concurrently — race condition eliminated without locking.

**Async trade flow:**
```
Client sends trade request
        ↓
App publishes to Kafka → returns "order received" immediately
        ↓
Frontend shows PENDING status
        ↓
Consumer processes trade, updates DB
        ↓
Push notification/websocket → frontend updates to FILLED or REJECTED
```
Never show "success" until the consumer confirms execution.

**Why async is better than sync under high load:**
Sync keeps the HTTP thread open and blocked while waiting for the DB. At 10,000 concurrent users that's 10,000 blocked threads. Async frees the thread immediately — a small consumer pool handles the DB work in the background.

**Where else to use Kafka in this project:**
- Notifications — publish `trade-completed` event, notification service consumes it and sends email/push
- Audit logging — durable event history even if DB goes down
- Price updates — scheduler publishes price events, multiple consumers update DB, Redis, trigger alerts

---

## 12. Docker

**What Docker is:**
A containerisation platform. Packages your application and all its dependencies into an **image** that runs identically everywhere — developer laptop, CI pipeline, production. Eliminates "works on my machine" problems.

**Image vs Container:**
- **Image** — the blueprint, built from a `Dockerfile`. Static, read-only. Like a Java class.
- **Container** — a running instance of an image. Like an object instantiated from a class.

**`Dockerfile`** — defines how to build your app's image:
```dockerfile
FROM eclipse-temurin:21-jre        # base Java 21 image
WORKDIR /app                        # working directory inside container
COPY target/trade-app.jar app.jar   # copy your built jar in
EXPOSE 8080                         # expose port
ENTRYPOINT ["java", "-jar", "app.jar"]
```

**`docker-compose.yml`** — runs multiple containers together as a full stack:
```yaml
services:
  app:
    build: .              # build from Dockerfile
    ports:
      - "8080:8080"
    depends_on:
      - redis
      - postgres

  redis:
    image: redis:7
    ports:
      - "6379:6379"

  postgres:
    image: postgres:15
    environment:
      POSTGRES_DB: tradedb
      POSTGRES_PASSWORD: secret
    ports:
      - "5432:5432"
```

One command starts the entire stack: `docker-compose up`

---

## 13. AWS Services

**S3 — Simple Storage Service:**
File and object storage. Used for images, documents, logs, backups, exported reports. Not a database — for files.

**RDS — Relational Database Service:**
Managed PostgreSQL (or MySQL etc.) on AWS. AWS handles backups, patching, failover. Replaces H2 in production.

**ECS — Elastic Container Service:**
Runs Docker containers on AWS. You provide the Docker image, ECS manages the servers. Simpler to operate.

**EKS — Elastic Kubernetes Service:**
Runs Docker containers using Kubernetes. More powerful and flexible than ECS but significantly more complex.

**ECS vs EKS:**
| | ECS | EKS |
|---|---|---|
| Orchestration | AWS proprietary | Kubernetes |
| Complexity | Simpler | More complex |
| Best for | Straightforward deployments | Large scale microservices |

**For this project: ECS** — no need for Kubernetes complexity at current scale.

**Lambda — Serverless functions:**
Run code without managing servers. Pay per execution. Good for: price alerts, scheduled reports, event processing. Not suitable for core trade API — cold start latency, stateless.

**Full AWS stack for this project:**
```
Client
  ↓
AWS ALB (Load Balancer)
  ↓
ECS — multiple Spring Boot containers
  ↓
ElastiCache (Redis) — price caching
  ↓
RDS (PostgreSQL) — main database
  ↓
S3 — logs, backups, reports
Lambda — notifications, scheduled reports
```

---

## 14. CI/CD Pipeline

**CI — Continuous Integration:**
Every time code is pushed, an automated pipeline builds the app and runs all tests. Catches bugs before they merge into main. No broken code gets through.

**CD — Continuous Deployment:**
After CI passes, automatically deploys to an environment:
- Push to `develop` → deploy to staging
- Push to `main` → deploy to production

**Pipeline for this project (GitHub Actions):**
```yaml
name: CI
on:
  push:
    branches: [ main, develop ]
  pull_request:
    branches: [ main ]

jobs:
  build-and-test:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v3

      - name: Set up Java 21
        uses: actions/setup-java@v3
        with:
          java-version: '21'

      - name: Build and test
        run: mvn clean verify

      - name: Check coverage
        run: mvn jacoco:check
```

**Full pipeline flow:**
```
Push to GitHub
        ↓
Build: mvn clean package
Test: mvn test (JUnit + integration tests)
Coverage: JaCoCo threshold check
Build Docker image
        ↓
All pass? → Push image to AWS ECR
        ↓
Deploy to ECS → zero downtime deployment
```

---

## 15. `@Scheduled` Risks in Multi-Instance Deployment

**The problem:**
`PriceUpdateScheduler` runs every 10 seconds on every app instance. With 3 instances, the DB gets 3 duplicate price rows every 10 seconds — wasteful and pollutes price history.

**Solution A — ShedLock:**
Uses a lock table in the DB. Only one instance acquires the lock and runs the scheduler. Others skip their run.
```java
@Scheduled(fixedRate = 10000)
@SchedulerLock(name = "priceUpdateScheduler",
               lockAtMostFor = "9s",
               lockAtLeastFor = "5s")
public void updatePrices() { ... }
```

**Solution B — Extract to dedicated microservice (best):**
Move the scheduler into its own single-instance service. Main app instances only read prices, never write them. Follows Single Responsibility — the price fetcher has one job.

**Solution C — External scheduler:**
AWS EventBridge or Kubernetes CronJob triggers price updates externally. No app instance runs the scheduler at all.

---

## 16. Code Review — What to Look For

**1. Correctness**
- Does the logic handle edge cases?
- Are null checks in place?
- Are the right fields passed to the right methods?

**2. Security**
- SQL injection — in MyBatis, always `#{}` not `${}`:
```xml
WHERE user_id = #{userId}   ✅ safe — parameterised
WHERE user_id = ${userId}   ❌ unsafe — string interpolation
```
- Are sensitive fields (passwords, tokens) ever logged or returned in responses?
- Is authorisation checked — can user A access user B's data?

**3. Performance**
- Any DB calls inside a loop? (N+1 problem)
- Are indexes used for new queries?
- Can any algorithm be simplified?

**4. Code quality**
- Are methods and variables named clearly?
- Is any method too long — should it be broken up?
- Is there duplicated code that should be extracted?
- Are magic numbers replaced with named constants?
```java
// Bad
if (quantity.compareTo(new BigDecimal("0.00000001")) < 0)

// Good
private static final BigDecimal MIN_QUANTITY = new BigDecimal("0.00000001");
if (quantity.compareTo(MIN_QUANTITY) < 0)
```

**5. Tests**
- Are there tests for the new code?
- Do tests cover happy path AND edge cases?
- Are assertions actually checking the right values?

**6. Design**
- Does new code follow existing patterns in the codebase?
- Is any class taking on too many responsibilities?
- Are exceptions handled with the right status codes and meaningful messages?

**7. Documentation**
- Are non-obvious decisions explained?
- Are API changes reflected in Swagger annotations?

---

## 17. Scaling to 10,000 Concurrent Users

**Full answer:**

1. **Multiple app instances + load balancer** — run multiple Spring Boot instances behind an AWS ALB. Each instance handles a share of traffic.

2. **API Gateway** — sits in front of the load balancer. Handles rate limiting (prevents bot spam), authentication, and routing.

3. **Redis cache** — cache coin prices with 10s TTL. Reduces DB reads dramatically — price only changes every 10s, no need to hit DB on every trade.

4. **Kafka for async trade processing** — partition by `userId`. All trades for the same user processed sequentially by one consumer. No concurrent writes, no locking needed. Frontend shows PENDING → FILLED/REJECTED.

5. **Optimistic locking** — version column on wallet rows as a safety net for concurrent writes:
```sql
UPDATE user_wallets
SET balance = #{newBalance}, version = version + 1
WHERE user_id = #{userId} AND version = #{expectedVersion}
```
If 0 rows updated → conflict → retry.

6. **Database sharding** — partition user data across multiple DB servers by `userId`. Distributes write load.

7. **HikariCP tuning** — tune `maximum-pool-size` so the connection pool doesn't become a bottleneck.

---

## 18. `BigDecimal` vs `double`

**The problem with `double`:**
`double` uses binary floating point. It cannot represent all decimal numbers exactly — same reason you can't write 1/3 as a finite decimal. The error is tiny but accumulates over many calculations.

```java
double a = 0.1 + 0.2;
System.out.println(a); // 0.30000000000000004 — wrong
```

**Why `BigDecimal`:**
Stores numbers as exact decimal values. No binary conversion, no rounding drift.
```java
BigDecimal a = new BigDecimal("0.1").add(new BigDecimal("0.2"));
System.out.println(a); // 0.3 — exact
```

**In trading:** a rounding error of `0.00000000004` BTC multiplied across millions of trades becomes real money. Auditors and regulators will not accept "it's a rounding error." `BigDecimal` is the only acceptable choice.

**When to use `double`:** scientific calculations where approximate values are acceptable, or performance-critical code where tiny errors don't matter.

---

## 19. Spring Boot Auto-Configuration

**`@SpringBootApplication` = 3 annotations combined:**
```java
@SpringBootConfiguration   // marks as configuration class
@EnableAutoConfiguration   // enables auto-configuration
@ComponentScan             // scans for @Component, @Service etc.
```

**Component scanning:**
Scans the package for classes annotated with `@Component`, `@Service`, `@Repository`, `@RestController`. Registers them as beans and wires dependencies between them automatically via constructor injection.

**Auto-configuration:**
Detects what's on the classpath and configures sensible defaults automatically — no boilerplate config needed:
- Added `spring-boot-starter-web` → Tomcat server auto-configured on port 8080
- Added `h2` → H2 datasource auto-configured
- Added `spring-boot-starter-security` → all endpoints locked down (why you needed `SecurityConfig` to permit all)

**The difference:**
- Component scanning — finds **your own** classes and wires them together
- Auto-configuration — configures **third party libraries** automatically based on classpath

---

## 20. `@Component` vs `@Service` vs `@Repository` vs `@RestController`

All are specialisations of `@Component` — all get picked up by component scanning.

```
@Component          — generic bean (use when nothing more specific fits)
  ├── @Service      — business logic layer
  ├── @Repository   — data access layer + exception translation
  └── @Controller   — web layer, returns views (JSP)
        └── @RestController  — @Controller + @ResponseBody, returns JSON
```

**`@Repository` extra behaviour:**
Translates database-specific exceptions (e.g. `SQLException`) into Spring's `DataAccessException`. Your service layer never has to deal with raw JDBC exceptions.

**`@RestController` vs `@Controller`:**
- `@Controller` — returns a view name (JSP/Thymeleaf template)
- `@RestController` — returns JSON directly (what your project uses)

---

## 21. Constructor vs Field vs Setter Injection

Three ways Spring can inject dependencies into a class:

**Constructor injection (recommended — what your project uses):**
```java
@Service
@RequiredArgsConstructor  // Lombok generates the constructor
public class TradeServiceImpl {
    private final TradeMapper tradeMapper;        // final — immutable
    private final UserWalletMapper userWalletMapper;
}
```

**Field injection (discouraged):**
```java
@Service
public class TradeServiceImpl {
    @Autowired
    private TradeMapper tradeMapper;  // not final, hidden dependency
}
```

**Setter injection:**
```java
@Service
public class TradeServiceImpl {
    private TradeMapper tradeMapper;

    @Autowired
    public void setTradeMapper(TradeMapper tradeMapper) {
        this.tradeMapper = tradeMapper;
    }
}
```

**Why constructor injection is best:**
| | Constructor | Field | Setter |
|---|---|---|---|
| Dependencies explicit | ✅ | ❌ | ✅ |
| Supports `final` (immutable) | ✅ | ❌ | ❌ |
| Testable without Spring | ✅ | ❌ | ✅ |
| Detects missing deps at startup | ✅ | ❌ | ❌ |

Field injection is discouraged because dependencies are hidden, fields can't be `final`, and you can't test without Spring context.

---

## 22. N+1 Query Problem

**The problem:**
Fetching a list then making individual DB calls for each item in a loop.

```java
// 1 query to get all trades
List<Trade> trades = tradeMapper.findAll();

// N queries — one per trade to get pair name
for (Trade trade : trades) {
    CryptoPair pair = cryptoPairMapper.findById(trade.getCryptoPairId());
}
// 100 trades = 101 DB queries. 10,000 trades = 10,001 queries.
```

**The fix — JOIN in SQL:**
```sql
SELECT t.*, cp.pair_name
FROM trades t
JOIN crypto_pairs cp ON cp.id = t.crypto_pair_id
```
One query returns everything. No loop, no extra DB calls.

---

## 23. Connection Pooling (HikariCP)

**What it is:**
A pool of pre-established database connections. Requests borrow a connection, use it, return it. Avoids the overhead of creating a new DB connection for every request (expensive and slow).

**HikariCP** is Spring Boot's default connection pool — auto-configured, no setup needed.

**Key settings:**
```yaml
spring:
  datasource:
    hikari:
      maximum-pool-size: 10      # max connections — most important setting
      minimum-idle: 5            # min idle connections kept ready
      connection-timeout: 30000  # wait time for a connection (ms)
      idle-timeout: 600000       # how long idle connection stays in pool
```

**`maximum-pool-size` tuning:**
- Too small → requests queue up waiting for a free connection
- Too large → DB server overwhelmed with concurrent connections
- Rule of thumb: `(CPU cores × 2) + disk spindles`

---

## 24. Production Troubleshooting

**When a trade endpoint returns 500 errors:**

**Step 1 — Gather information:**
- When did it start? Was there a recent deployment?
- Which users are affected — all or specific?
- Is it 100% of requests or intermittent?

**Step 2 — Check logs (AWS CloudWatch):**
- Find the stack trace — exact exception and line number
- Look for patterns — same user? same pair? started at a specific time?

**Step 3 — Check infrastructure:**
- Is the DB reachable? Is the connection pool exhausted?
- Is Redis up?
- Did Binance/Huobi API go down, causing the scheduler to fail?
- CPU/memory spikes on app servers?

**Step 4 — Reproduce in dev:**
- Replicate the exact request that caused the 500
- Check recent code changes — `git log`, `git diff`
- Run with debug logging enabled

**Step 5 — Fix and verify:**
- Write a test that catches the bug first
- Fix in dev, verify test passes
- Deploy to staging, confirm fix
- Deploy to production, monitor logs confirm 500s stop

**Key principle:** always check logs before guessing. Most production bugs are caused by recent deployments — ask "what changed?" first.

---

## 25. SQL Quick Reference

**Clause order (must be in this sequence):**
```sql
SELECT
FROM
JOIN
WHERE       -- filters rows before grouping
GROUP BY
HAVING      -- filters groups after aggregation
ORDER BY
LIMIT
```

**JOIN example — trades with username and pair name:**
```sql
SELECT u.username, c.pair_name, t.trade_type, t.quantity, t.total_amount
FROM trades t
JOIN users u ON u.id = t.user_id
JOIN crypto_pairs c ON c.id = t.crypto_pair_id;
```

**GROUP BY + SUM — total spent per user:**
```sql
SELECT u.username, SUM(t.total_amount) AS total_spent
FROM trades t
JOIN users u ON u.id = t.user_id
WHERE t.trade_type = 'BUY'
GROUP BY u.username
HAVING total_spent > 5000
ORDER BY total_spent DESC;
```

**WHERE vs HAVING:**
- `WHERE` — filters individual rows before grouping
- `HAVING` — filters groups after aggregation (use with GROUP BY)

**Latest price query:**
```sql
SELECT * FROM crypto_prices
WHERE crypto_pair_id = #{cryptoPairId}
ORDER BY created_at DESC
LIMIT 1;
```

---

*Good luck with the interview. You've covered everything.*
