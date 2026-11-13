# Shortly — URL shortener with analytics

Spring Boot 3.5 · Java 21 · PostgreSQL · Redis

A small service that is deliberately built the way a production one would be: collision-free
code generation, cache-aside reads with negative caching, a distributed token-bucket rate
limiter, and click analytics that never slow down a redirect.

## Run it

Only Docker is required.

```bash
docker compose up -d --build
```

```bash
curl -s -X POST localhost:8080/api/v1/urls -H "Content-Type: application/json" -d '{"url":"https://example.com/a/very/long/path"}'
```

```bash
curl -i localhost:8080/<code>
```

```bash
curl -s localhost:8080/api/v1/urls/<code>/stats
```

Run the tests (Testcontainers starts real Postgres and Redis):

```bash
docker run --rm -v "$PWD:/app" -w /app -v shortly-m2:/root/.m2 -v /var/run/docker.sock:/var/run/docker.sock -e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal maven:3.9-eclipse-temurin-21 mvn -B -ntp test
```

With a local JDK 21 + Maven, plain `mvn test` and `mvn spring-boot:run` work too.
(On Git Bash for Windows, prefix the `docker run` with `MSYS_NO_PATHCONV=1` and use `$(pwd -W)`.)

## API

| Method | Path | Purpose |
|---|---|---|
| `POST` | `/api/v1/urls` | Create. Body: `{"url": "...", "alias": "optional", "ttlSeconds": optional}` → `201` |
| `GET` | `/{code}` | Redirect → `302`, `404` unknown, `410` expired |
| `GET` | `/api/v1/urls/{code}` | Link metadata |
| `GET` | `/api/v1/urls/{code}/stats?days=30` | Total clicks, unique visitors, clicks per day, top referrers, browsers |

Errors are RFC 9457 `application/problem+json`. Rate-limited responses are `429` with `Retry-After`.

## Architecture

```
            ┌──────────────── redirect (hot path, ~99% of traffic) ───────────────┐
 client ──► │ rate limit (Redis Lua) ─► Redis GET url:{code} ─► 302               │
            │                              │ miss                                 │
            │                              ▼                                      │
            │                        Postgres SELECT ─► cache (or cache "!missing")│
            │ click ─► bounded in-memory queue (non-blocking offer)               │
            └──────────────────────────────┬──────────────────────────────────────┘
                                           ▼  every 2s, batches of 500
                       INSERT click_events (batch) + UPDATE click_count (per code)
```

| Concern | Where |
|---|---|
| Code generation | [ShortCodeGenerator.java](src/main/java/com/shortly/url/ShortCodeGenerator.java) |
| Create / resolve, collision retry | [UrlService.java](src/main/java/com/shortly/url/UrlService.java) |
| Redis cache | [UrlCache.java](src/main/java/com/shortly/url/UrlCache.java) |
| Rate limiting | [token_bucket.lua](src/main/resources/scripts/token_bucket.lua), [RateLimiter.java](src/main/java/com/shortly/ratelimit/RateLimiter.java) |
| Click tracking | [ClickTracker.java](src/main/java/com/shortly/analytics/ClickTracker.java) |
| Analytics queries | [ClickRepository.java](src/main/java/com/shortly/analytics/ClickRepository.java) |
| Schema | [V1__init.sql](src/main/resources/db/migration/V1__init.sql) |

## Design decisions (the interview part)

### 1. Short codes: counter + Base62, not hashing

Three standard options:

| Approach | Collisions | Cost |
|---|---|---|
| Hash the URL (MD5/SHA-256), truncate to 7 chars | Yes — birthday bound: ~50% chance of a collision after only ~2.2M URLs in a 62⁷ space | Must check-and-retry on every insert |
| Random 7 chars | Yes, same maths | Same check-and-retry |
| **Unique counter → Base62** (chosen) | **None by construction** | Needs a unique ID source |

The ID comes from a Postgres sequence, so uniqueness is already solved; Base62 just renders it.
7 characters = 62⁷ ≈ 3.5 trillion codes (~110 years at 1,000 creates/second).

**Problem with a raw counter:** codes are sequential (`…0001`, `…0002`), which leaks volume and
lets anyone enumerate every link. Fix: scramble the ID first with `(id × M) mod 62⁷`, where M is
coprime to 62⁷. That is a *bijection* — still zero collisions — but consecutive IDs land far apart.
It is obfuscation, not cryptography; if unguessable links were a requirement, swap in a keyed
Feistel network / format-preserving encryption, or use longer random codes.

**Should the same long URL return the same code?** Not here: every create gets a new code, so two
users shortening the same URL get independent analytics and expiry. Deduplication would need a
unique index on a hash of the URL — that is the one place hashing does belong.

### 2. Where collisions still happen: custom aliases

A user can claim an alias that equals a code the generator will produce later. Handling:

- `code` has a **unique constraint** — the database is the only arbiter, so there is no
  check-then-insert race between concurrent requests or instances.
- Inserts use `ON CONFLICT (code) DO NOTHING` and inspect the row count.
- Alias conflict → `409`. Generated-code conflict → take the next sequence value and retry
  (bounded at 5 attempts). `generatedCodeSkipsOverACollidingCustomAlias` tests exactly this.

### 3. Redirect is 302, not 301

A 301 is cached by browsers, so repeat visits never reach us: click counts go wrong and expiry /
deletion stops working. 302 + `Cache-Control: no-store` costs one hop per click, which is the thing
being measured. (If analytics did not matter, 301 would be the cheaper choice.)

### 4. Redis cache

Read-heavy workload (reads ≫ writes), immutable mappings → ideal for cache-aside.

- **Cache penetration** (scanning random codes hits the DB every time): unknown and expired codes
  are cached as `!missing` / `!gone` sentinels with a short TTL.
- **Cache avalanche** (many keys expiring together): TTL has ±10% jitter.
- **Correctness with expiry**: the Redis TTL is capped at the link's remaining lifetime.
- **Write-through on create**: warms the first click and overwrites a stale `!missing` entry.
- **Redis down**: every cache call fails open with a 250 ms timeout; Postgres remains the source of truth.
- **Eviction**: `allkeys-lru` with a memory cap — it is a cache, hot links stay, cold ones fall out.
- Not implemented: stampede protection for a single very hot key expiring (per-key lock or
  probabilistic early refresh). With immutable values, the worst case is a few duplicate SELECTs.

### 5. Rate limiting

Token bucket per client IP, state in Redis, refill + take in one **Lua script** (atomic, so N app
instances share one consistent bucket; time comes from Redis, not from drifting app clocks).

- Token bucket vs fixed window: allows bursts, no "2× limit across the window boundary" problem.
- Separate budgets: creating is expensive and abusable (10/min), redirects are cheap (burst 100, 20/s).
- **Fails open** when Redis is unavailable: losing protection briefly beats a full outage.
- Client identity is `getRemoteAddr()`. Behind a load balancer set `FORWARD_HEADERS_STRATEGY=native`
  so the container resolves `X-Forwarded-For` from the trusted proxy — never parse that header by hand.

### 6. Click tracking

The redirect must not wait on an analytics write, so the request thread only does a non-blocking
`offer` into a bounded queue. A scheduled task drains it and writes one batched insert plus one
counter update per distinct code, in a single transaction.

- Bounded queue = **backpressure by shedding analytics**, never redirects (`shortly.clicks.dropped` metric).
- Stats are **eventually consistent** (lag ≤ flush interval).
- Events in memory are lost on a hard kill (graceful shutdown flushes). If clicks were billable,
  replace the queue with Kafka / Redis Streams behind the same `record()` call.
- Privacy: only the referrer *host* and a salted hash of the IP are stored — enough for
  "unique visitors" without keeping raw IPs or URLs that may contain tokens.

### 7. Scaling path

Rough numbers: 100M new URLs/month ≈ 40 writes/s; at 100:1 that is ~4,000 redirects/s.
~500 bytes/row → ~50 GB/month of mappings; click events dwarf that.

1. **App tier** is stateless → add instances behind a load balancer. (Each instance has its own click buffer; that is fine.)
2. **Reads**: Redis absorbs most lookups; add Postgres read replicas, then a CDN/edge cache for the hottest links if analytics can be sampled.
3. **ID generation** becomes the write bottleneck before storage does: hand each instance a *range* of IDs (e.g. sequence `INCREMENT BY 1000`, or ranges leased from ZooKeeper/etcd), or switch to Snowflake-style IDs (which need longer codes).
4. **Storage**: lookups are pure key→value, so shard `short_urls` by hash of `code`, or move it to a KV store (DynamoDB/Cassandra).
5. **Analytics**: clicks → Kafka → stream aggregation → columnar store (ClickHouse); partition `click_events` by time and serve dashboards from pre-aggregated rollups instead of `COUNT(*)`.
6. **Redis**: cluster mode; rate-limit keys and cache keys can live in separate instances so a cache flush never resets limits.

## Not included (on purpose)

- **Auth / ownership** — anyone can create links and read stats; there is no delete endpoint because
  without ownership it would let anyone delete anything.
- **Malicious-URL screening** (Safe Browsing lookup) and abuse reporting.
- **Geo analytics** (needs a GeoIP database).
