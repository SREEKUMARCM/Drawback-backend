# Drawback backend (Spring Boot microservices)

Java 21 · Spring Boot 3.5 · PostgreSQL · Kafka · Solr · Redis · gRPC · JWT · JUnit 5 / Mockito / Testcontainers

> **Status:** written without the ability to download Maven dependencies, so it has **not been compiled or run yet**.
> All 88 Java files were syntax-checked with `javac` (parse only). Run `mvn verify` first and expect to fix a few small
> API/version mismatches — the places most likely to need attention are listed at the bottom.

```
common-events        event contracts + shared Kafka retry/dead-letter policy
common-security      JWT filter chain, RBAC, Redis blacklist, error model (auto-configuration)
common-grpc          user_directory.proto + JWT propagation/verification for gRPC
auth-service         register/login/refresh/logout, RBAC admin, gRPC server, user stats      :8081
review-service       subjects & reviews, outbox → Kafka, Redis cache, parallel aggregation   :8082
search-service       Kafka → Solr indexer + search API                                       :8083
notification-service review events → notifications, async channel fan-out                    :8084
deploy/docker        docker-compose (Postgres, Redis, Kafka KRaft, Solr) + Dockerfile
deploy/k8s           production-shaped manifest (probes, HPA, PDB, NetworkPolicy)
docs                 ARCHITECTURE.md, DEPLOYMENT.md
```

## Run locally
```bash
export JWT_SECRET=$(openssl rand -base64 48)
docker compose -f deploy/docker/docker-compose.yml up -d --build      # whole stack
# or only the infrastructure, then run services from the IDE / mvn:
docker compose -f deploy/docker/docker-compose.yml up -d postgres redis kafka solr
mvn -q -DskipTests install && mvn -pl review-service spring-boot:run
```

## Try it
```bash
A=http://localhost:8081/api/v1/auth
curl -s -XPOST $A/register -H 'content-type: application/json' \
  -d '{"username":"alice","email":"alice@example.com","password":"correct-horse-battery","displayName":"Alice"}'
TOKEN=$(curl -s -XPOST $A/login -H 'content-type: application/json' \
  -d '{"identifier":"alice","password":"correct-horse-battery"}' | jq -r .accessToken)
ID=00000000-0000-0000-0000-000000000000
curl -si localhost:8082/api/v1/subjects/$ID                                     # 401: no token
curl -si localhost:8082/api/v1/subjects/$ID -H "authorization: Bearer $TOKEN"   # 404: authenticated, subject unknown
```
Reviewing requires a **verified** account: have an admin call `PUT /api/v1/admin/users/{id}/verification` (needs MODERATOR+;
bootstrap the first admin with SQL: `insert into user_roles values ('<id>','ADMIN')`), then log in again so the token carries `verified=true`.

## Main endpoints (all need `Authorization: Bearer <jwt>` except register/login/refresh)
| | |
|---|---|
| auth | `POST /api/v1/auth/{register,login,refresh,logout,logout-all}` · `GET /api/v1/auth/me` · `PUT /api/v1/admin/users/{id}/{roles,suspension,reinstatement,verification}` |
| review | `POST /api/v1/subjects` · `GET /api/v1/subjects/{id}` · `GET/POST /api/v1/subjects/{id}/reviews` · `GET/PUT/DELETE /api/v1/reviews/{id}` · `GET /api/v1/reviews/{id}/detail` · `POST /api/v1/admin/reconcile` |
| search | `GET /api/v1/search?q=` · `GET /api/v1/search/reviews?q&category&minRating&sort&page&size` · `GET /api/v1/search/suggest?q=` |
| notification | `GET /api/v1/notifications` · `POST /api/v1/notifications/{id}/read` · `POST /api/v1/notifications/read-all` |

## Tests
`mvn test` — unit tests (JwtService, filter, full security chain via MockMvc, AuthService, ReviewCommandService, parallel
detail aggregation, outbox ordering, Solr indexer/query building, notification idempotency).
`mvn verify` — adds Testcontainers integration tests (`*IT`: auth lifecycle with real Postgres+Redis; review flow with
Postgres+Redis+Kafka incl. 40-way concurrent reviewers and reconciliation). Docker required.

## Things to verify first when you build (couldn't be checked offline)
1. **Versions**: Boot 3.5.0 is pinned in the parent POM; bump to your newest 3.x. Boot 4 changes Jackson/starters.
2. **gRPC stack**: `net.devh` 3.1.0 with `grpc-bom` 1.64 / protobuf 3.25.5, and the `io.github.ascopes:protobuf-maven-plugin` config in `common-grpc/pom.xml`. If you prefer, replace with the official `spring-grpc` starter.
3. **Integration test props** `grpc.server.port=-1` / `in-process-name` (disables the Netty gRPC server in `AuthFlowIT`).
4. **Testcontainers**: `org.testcontainers.kafka.KafkaContainer` + `apache/kafka-native` image requires Testcontainers ≥1.20.
5. **Solr**: `Http2SolrClient.Builder` timeouts and `SchemaRequest` calls target SolrJ 9.7.
6. Redis cache serializer: `Jackson2JsonRedisSerializer(ObjectMapper, Class)` may be flagged deprecated in newer Spring Data Redis.

See `docs/ARCHITECTURE.md` for design decisions and `docs/DEPLOYMENT.md` for the production checklist.
# Drawback-backend
