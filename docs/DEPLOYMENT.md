# Production deployment checklist

## Secrets & identity
- `JWT_SECRET` (≥32 random bytes, base64) from a secret manager (Vault / AWS Secrets Manager / Kubernetes secrets with KMS encryption). **Rotate** by supporting two keys (`kid`) during a roll; move to RS256/EdDSA when you can.
- Per-service database users with least privilege; Flyway runs with a migration user, the app with a DML-only user (set `spring.flyway.user/password`).
- TLS everywhere: ingress → gateway, and service-to-service (mTLS via a mesh such as Linkerd/Istio). Switch `grpc.client.*.negotiation-type` from `plaintext` to `tls`.
- Never expose the management port (`9081–9084`) publicly; scrape it inside the cluster only (NetworkPolicy provided).

## Database (PostgreSQL)
- One database per service, managed HA (RDS/Cloud SQL) with PITR backups; test restores.
- Hikari pool ≈ `(cores × 2) + disks` per instance; total connections across replicas must stay under `max_connections` (use PgBouncer if replicas × pool is large).
- `ddl-auto=validate` + Flyway only. Make migrations backward compatible (expand → migrate → contract) so rolling deploys are safe.
- Add read replicas for list endpoints only after measuring; the hot paths are already cached.

## Kafka
- ≥3 brokers, `replication.factor=3`, `min.insync.replicas=2`, producers `acks=all` + idempotence (already set).
- Create topics explicitly (don't rely on auto-create): `drawback.reviews.v1`, `drawback.subjects.v1` with e.g. 12 partitions, plus their `.DLT` topics. Partition count is your max consumer parallelism — pick it with headroom.
- Alert on consumer lag and on any message arriving on a `.DLT` topic; build a small replay tool for the DLT.
- Evolve event schemas additively (`@JsonIgnoreProperties(ignoreUnknown)` is on); for breaking changes publish `*.v2` in parallel. Add Schema Registry (Avro/Protobuf) if many teams produce events.

## Solr
- SolrCloud: ≥3 ZooKeeper nodes, collections with 2+ shards × 2 replicas, `commitWithin` (not hard commits) from clients.
- Define fields via the Schema API / versioned configset (the service does this idempotently on start; for prod prefer shipping the configset and setting `drawback.search.solr.init-schema=false`).
- Snapshot indexes, but remember they are rebuildable from Kafka.

## Redis
- Use a replicated, persistent deployment (Sentinel/Cluster or managed). Set `maxmemory` + `volatile-lru`. Blacklist and refresh-token keys all have TTLs.
- Redis is on the auth path: size it, monitor it, alert on latency. Behaviour on outage: token validation **fails closed (503)**; search and entity caches degrade to the database.

## Runtime
- JVM: `-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError` (set in the Dockerfile); memory limit = request; avoid CPU limits (CFS throttling hurts tail latency).
- Graceful shutdown is on (`server.shutdown=graceful`); `terminationGracePeriodSeconds` must exceed it, and a `preStop` sleep lets the load balancer drain first.
- Health: `/actuator/health/liveness` and `/readiness` on the management port; keep DB/Kafka checks out of *liveness*.
- Run the outbox poller and the reconciliation job safely with multiple replicas: the poller uses `SKIP LOCKED`; trigger reconciliation from **one** Kubernetes CronJob calling `POST /api/v1/admin/reconcile`.

## Observability
- Metrics: Micrometer → Prometheus (JVM, Hikari, HTTP, Kafka client). Add dashboards for p95/p99 latency, consumer lag, outbox backlog (`count(*) where published_at is null`), cache hit ratio, 401/403/429 rates.
- Tracing: add `micrometer-tracing-bridge-otel` + OTLP exporter; propagate `traceparent` over HTTP, gRPC and Kafka headers.
- Logging: JSON logs with correlation ids; never log tokens, passwords or full request bodies.

## Edge
- API gateway: TLS, global rate limiting, request size limits, CORS allow-list, WAF. Terminate JWT *again* in each service (already done) — the gateway is not the only line of defence.
- Set `server.forward-headers-strategy=native` behind a trusted proxy so rate-limiting sees real client IPs.

## Delivery
- CI: `mvn verify` (unit + Testcontainers integration tests), dependency & image scanning (OWASP dependency-check/Trivy), SBOM.
- Deploy: immutable images pinned by digest, rolling updates with `maxUnavailable: 0`, readiness gates, canary for risky changes. Roll back by redeploying the previous digest — migrations must therefore be backward compatible.
- Load test (k6/Gatling) before launch: check pool sizes, rejected-executor rate and Solr/Redis latency at 2× expected peak.
