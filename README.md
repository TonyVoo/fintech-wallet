# Fintech Wallet

E-wallet / payment system built as microservices with Java and Spring Boot.

## Roadmap
- [x] account-service: accounts, deposit/withdraw, optimistic locking, Flyway, JWT resource server with per-owner access control
- [x] auth-service: register/login, JWT access token, rotating refresh tokens with reuse detection (port 8092)
- [ ] transaction-service: transfers, double-entry ledger, idempotency keys
- [ ] Kafka: outbox pattern, notification-service
- [ ] api-gateway, Redis rate limit
- [x] Testcontainers integration tests (`mvn verify`)
- [ ] CI (GitHub Actions)
- [ ] Deploy (AWS), load test, architecture diagram

## Run locally
```bash
docker compose up -d                       # PostgreSQL
mvn -pl account-service spring-boot:run    # http://localhost:8091
```

## Tests
```bash
mvn test      # unit tests only, no Docker needed
mvn verify    # unit + integration tests (*IT) against real PostgreSQL via Testcontainers, needs Docker
```

## Try it
```bash
curl -X POST localhost:8091/api/accounts -H 'Content-Type: application/json' \
  -d '{"ownerName":"Nguyen Van A","currency":"VND"}'
```
