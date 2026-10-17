# Architecture

## 1. Overview

```
                         ┌──────────────────────────────────────────────┐
   clients ──HTTPS──►    │  API gateway / ingress  (TLS, rate limit, WAF)│
                         └───────┬───────────┬───────────┬──────────┬───┘
                                 │ JWT       │ JWT       │ JWT      │ JWT
                          ┌──────▼────┐ ┌────▼──────┐ ┌──▼───────┐ ┌▼───────────────┐
                          │   auth    │ │  review   │ │  search  │ │  notification  │
                          │  :8081    │ │  :8082    │ │  :8083   │ │  :8084         │
                          └──┬──┬─────┘ └─┬──┬──┬───┘ └──┬────┬──┘ └──┬─────────────┘
              gRPC (JWT) ◄───┘  │         │  │  │        │    │       │
              GetUser(s)        │         │  │  └─outbox─┼────┼──────►│ Kafka
                                │         │  │     poll  │    │       │
                         ┌──────▼──┐  ┌───▼──▼──┐   ┌────▼┐ ┌─▼────┐ ┌─▼────────┐
                         │auth_db  │  │review_db│   │Solr │ │Redis │ │notif_db  │
                         │(Postgres)│ │(Postgres)│  └─────┘ └──────┘ └──────────┘
                         └─────────┘  └─────────┘
```

| Service | Owns | Talks to |
|---|---|---|
| **auth-service** | users, roles, refresh tokens (Redis), user stats | gRPC **server** (`UserDirectory`), consumes `reviews.v1` (stats) |
| **review-service** | subjects, reviews, rating aggregate, outbox | gRPC **client** → auth; produces `reviews.v1`, `subjects.v1` |
| **search-service** | Solr indexes (derived data) | consumes `reviews.v1`, `subjects.v1` → Solr |
| **notification-service** | notifications | consumes `reviews.v1`; email/push channels |

Rules that keep the system decoupled: **one database per service** (no shared tables), **events for facts that already happened** (Kafka), **gRPC for questions that need an answer now** (user lookup), and **everything derived (search index, counters) can be rebuilt from the event log**.

## 2. Security (every call authenticated)

* `common-security` is an auto-configuration every service picks up by adding the dependency. It builds a stateless `SecurityFilterChain`:
  `anyRequest().authenticated()` after a short, explicit allow-list (`drawback.security.public-paths`).
  The only unauthenticated business endpoints in the platform are **register / login / refresh**, because they are how a token is obtained. Everything else, including logout, `/me`, gRPC and unknown URLs, returns 401 without a valid token.
* `JwtAuthenticationFilter` runs on every request: parse + verify signature/issuer/expiry → check the Redis blacklist by `jti` → populate the `SecurityContext`. A blacklist outage **fails closed** (503).
* **RBAC**: roles `USER < MODERATOR < ADMIN` through a `RoleHierarchy`; enforced with `@PreAuthorize` (method security) plus ownership checks in the service layer (only the author edits; author or staff deletes).
* Tokens: 15-minute HS256 access JWT (roles + `verified` claim), opaque 256-bit **rotating refresh tokens** stored hashed in Redis with **reuse detection** (replay of a used token revokes that user's whole token family). Logout blacklists the access token's `jti` until it would have expired.
* Brute force: 5 failed logins / 15 min per (identifier, IP) in Redis; unknown users still pay a BCrypt comparison so timing does not reveal which accounts exist.
* gRPC carries the **same JWT** in metadata; `GrpcJwtServerInterceptor` rejects anything else with `UNAUTHENTICATED`.
* Production upgrade path: switch to RS256/EdDSA so only auth-service holds the private key and others verify with a JWKS endpoint; terminate mTLS in a mesh.

## 3. The review write path (and why it is shaped this way)

```
POST /subjects/{id}/reviews
  1  JWT checks            (no I/O)                          verified? role?
  2  gRPC auth.GetUser     (outside any DB transaction)      still active? deadline 500 ms
  3  ┌──────────── one short DB transaction ──────────────┐
     │ insert review  (unique subject+author)             │
     │ UPDATE subject SET count=count+1, sum=sum+:rating  │  atomic: no lost updates
     │ insert outbox rows: ReviewEvent, SubjectEvent      │
     └────────────────────────────────────────────────────┘
  4  evict Redis cache entries
  ...  OutboxPublisher (every 500 ms): SELECT … FOR UPDATE SKIP LOCKED → Kafka (acks=all, idempotent) → mark published
```

* **Transactional outbox**: the business row and its event commit atomically, so there is no "saved but never announced" or "announced but rolled back". Delivery is at-least-once; **every consumer is idempotent** (Solr overwrites by id; auth stats and notifications use a unique constraint on `event_id`).
* **Ordering**: events are keyed by entity id, so Kafka orders per entity. The publisher refuses to send a later event of a key after an earlier one failed.
* **Aggregate**: `average = rating_sum / review_count`, maintained with a single atomic `UPDATE`. A scheduled/admin **reconciliation job** recomputes from the rows (parallel chunks, `SELECT … FOR UPDATE` per subject) and repairs drift.
* Rating is a 0–100 integer, so the subject score is the arithmetic mean of all review ratings (same model as the Drawback frontend's agreement vote).

## 4. Search (Solr) synchronisation

`review-service → outbox → Kafka → search-service → Solr`

* Batch listener: one poll (≤500 records) becomes one Solr update request; within a batch only the latest event per entity is applied. `commitWithin=1s` gives near-real-time visibility without a commit per document.
* Failure policy (shared `common-events`): 4 retries with exponential backoff, then the batch goes to `<topic>.DLT`. Poison messages never block a partition.
* **Rebuild**: Solr is derived data. Reset the consumer group offset to earliest (or drop the core) and the index is rebuilt from Kafka.
* Query side: `edismax` with field boosts + phrase boost, filters passed as parameters (no query injection), sort whitelist with a deterministic tie-break, size/offset caps, HTML-encoded highlighting, category + rating facets, prefix suggest. Hot queries are cached in Redis for 30–60 s; a Redis failure never fails a search.

## 5. Concurrency, where it is actually used

| Place | Technique | Why |
|---|---|---|
| `ReviewDetailService` | `CompletableFuture` fan-out on a bounded `ioExecutor` (review → subject ∥ other reviews ∥ author-over-gRPC), `completeOnTimeout` | latency = slowest branch, not the sum; slow auth-service degrades one field instead of the page |
| `SearchService.searchAll` | parallel Solr queries (reviews ∥ subjects) | independent round-trips |
| `ReconciliationService` | chunked batch on a fixed pool sized to cores, `CallerRunsPolicy` back-pressure | repair thousands of subjects quickly without starving requests |
| `OutboxPublisher` | pipelined async sends, wait with `allOf`, per-key ordering guard | throughput without reordering |
| `ChannelDispatcher` | `@Async` pool + parallel channels, after-commit trigger | slow email/push never blocks the Kafka consumer |
| Kafka listeners | `concurrency=3` per consumer group | scale by partition |
| Writes | atomic SQL increments, optimistic `@Version`, `SKIP LOCKED` | correctness under concurrency without global locks |

Executors are **bounded** on purpose; overload becomes a fast 503 (`TaskRejectedException`) instead of unbounded queueing. On Java 21, virtual threads are enabled for the simple services (`spring.threads.virtual.enabled`).

## 6. Caching (Redis)

* Token blacklist (TTL = token's remaining life), refresh tokens (hashed), login throttling.
* Review and subject reads: Spring Cache with typed JSON serializers, 10 min / 2 min TTL **plus explicit eviction on every write**.
* Search result cache (30–60 s).

## 7. Trade-offs to know about

* HS256 shared secret is simplest; asymmetric keys are better beyond a handful of services.
* A suspended user's *existing access token* stays valid until it expires (≤15 min); review-service re-checks account status over gRPC for writes. Add a per-user "revoked-since" key in Redis if you need instant cut-off for reads too.
* The outbox poller adds up to `poll-ms` latency (500 ms). Use Debezium CDC if you need lower latency at higher volume.
* Subject owner is the creator; there is no ownership transfer or merge workflow yet.
