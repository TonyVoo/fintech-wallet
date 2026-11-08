# Fintech Wallet

E-wallet / payment system built as microservices with Java 17 and Spring Boot 3.

| Service | Port | Responsibility |
|---|---|---|
| auth-service | 8092 | Register / login, JWT access tokens, rotating refresh tokens |
| account-service | 8091 | Accounts and balances, per-owner access control, idempotent internal debit/credit |
| transaction-service | 8093 | Money transfers between accounts (saga), double-entry ledger, crash recovery, transactional outbox |
| notification-service | 8094 | Consumes transfer events from Kafka and stores per-user notifications |

Each service owns its own PostgreSQL database (ports 5443, 5442, 5444, 5445). Kafka runs on port 9094.

## Roadmap
- [x] auth-service: register/login, JWT access token, rotating refresh tokens with reuse detection
- [x] account-service: accounts, deposit/withdraw, optimistic locking + retry, JWT resource server, per-owner access control
- [x] transaction-service: idempotent transfers, saga with compensation, double-entry ledger, recovery job
- [x] Testcontainers integration tests (`mvn verify`), CI (GitHub Actions)
- [x] Kafka: transactional outbox in transaction-service, idempotent consumer + dead-letter topic in notification-service
- [ ] api-gateway, Redis rate limit
- [ ] Deploy (AWS), load test, architecture diagram

## How a transfer works

```
client --POST /api/transfers (Idempotency-Key)--> transaction-service
   1. validate: caller owns the source, destination exists, same currency
   2. insert transfer  PENDING          (unique per user + Idempotency-Key)
   3. debit  source      via account-service   --> DEBITED
   4. credit destination via account-service   --> COMPLETED  (+ 2 ledger entries, same DB transaction)
        |__ destination gone? credit the source back  --> REFUNDED
```

Why it is safe when things go wrong:

- **Retries / double clicks:** the same `Idempotency-Key` returns the original transfer instead of moving money again.
- **Timeouts and crashes:** every call to account-service carries an operation id derived from the transfer id, and
  account-service applies each operation id at most once. "No answer" is never treated as "failed": the transfer stays
  `PENDING`/`DEBITED` and a recovery job re-sends the same operation ids until it finishes.
- **No database transaction is held across a call to another service.** Each state change is its own short transaction
  that locks the transfer row, so a request and the recovery job can never step on each other. (The one deliberate
  exception is the outbox publisher, which holds row locks on the current small batch of outbox rows while it waits
  for Kafka's acknowledgement, with a timeout; no request waits for it.)
- **Overdraft:** a debit is refused when funds are short, even for parallel transfers from one account.
- **Ledger:** each completed transfer books one DEBIT and one CREDIT of the same amount (append-only).

Response codes of `POST /api/transfers`: `201` completed, `200` replay of an earlier request, `202` accepted but still
finishing (poll `GET /api/transfers/{id}`), `422` refused or rolled back (see `failureReason`), `400` bad input,
`404` unknown or not-yours account, `503` a required service is down (nothing was created).

## How events reach Kafka (transactional outbox)

When a transfer reaches a final state, transaction-service must both update its database and tell the rest of the
system. Writing to the database and to Kafka cannot be one atomic operation, so doing both directly can lose the
event (crash in between) or announce something that was rolled back.

```
transaction-service                                              notification-service
  one DB transaction:                                              Kafka topic `transfer-events`
    transfer -> COMPLETED                                                 |
    ledger entries                  OutboxPublisher                       v
    outbox_events row  ------------> polls unpublished rows ----> consumer (idempotent)
                                     sends to Kafka, waits for ack       |
                                     then marks the row published        v
                                                                   notifications table
                                                                   (unique per event + user)
```

- The event row commits or rolls back **together with** the state change, so it can never be lost or invented.
- Kafka down? Transfers keep working; events wait in the table and are sent when the broker is back.
- Delivery is **at-least-once**: a crash after Kafka acknowledged but before the row was marked published sends the
  event again. The consumer therefore ignores events it has already seen (event id check plus a unique constraint).
- The message key is the transfer id, so all events of one transfer land in the same partition, in order.
- A message the consumer can never process goes to the dead-letter topic `transfer-events.DLT` instead of blocking
  everything behind it; transient errors are retried twice first.

## Run locally
```bash
docker compose up -d                                  # PostgreSQL x4 + Kafka
mvn -pl auth-service spring-boot:run                  # http://localhost:8092
mvn -pl account-service spring-boot:run               # http://localhost:8091
mvn -pl transaction-service spring-boot:run           # http://localhost:8093
mvn -pl notification-service spring-boot:run          # http://localhost:8094
```

## Tests
```bash
mvn test      # unit tests only, no Docker needed
mvn verify    # unit + integration tests (*IT) against real PostgreSQL and Kafka via Testcontainers, needs Docker
```
The integration tests use `apache/kafka:3.8.0`: Testcontainers 1.21 does not start 3.9.0 (it rejects the generated
`advertised.listeners`). docker-compose uses 3.9.0 with its own configuration.

## Try it
```bash
# 1. register + login (returns an accessToken), then create accounts with it
curl -X POST localhost:8092/api/auth/register -H 'Content-Type: application/json' \
  -d '{"email":"alice@example.com","password":"password123"}'
curl -X POST localhost:8092/api/auth/login -H 'Content-Type: application/json' \
  -d '{"email":"alice@example.com","password":"password123"}'
curl -X POST localhost:8091/api/accounts -H 'Content-Type: application/json' -H "Authorization: Bearer $TOKEN" \
  -d '{"ownerName":"Alice","currency":"VND"}'

# 2. transfer (the Idempotency-Key is chosen by the client, one per intended transfer)
curl -X POST localhost:8093/api/transfers -H 'Content-Type: application/json' -H "Authorization: Bearer $TOKEN" \
  -H "Idempotency-Key: $(uuidgen)" \
  -d '{"fromAccountId":"<id>","toAccountId":"<id>","amount":300}'

# 3. a moment later both users see a notification (use each user's own token)
curl localhost:8094/api/notifications -H "Authorization: Bearer $TOKEN"
```

## Known limitations
- The development JWT secret, service API key and database passwords in the config files are for local use only;
  set `JWT_SECRET` and `INTERNAL_API_KEY` in any real environment.
- Owners can still deposit into their own account directly (`/deposit`), which stands in for a top-up. In a real
  system that endpoint would be restricted to a payment-provider integration.
- Recovery retries a stuck transfer forever at a fixed interval; there is no alerting yet.
- Dead-lettered Kafka messages are only stored in `transfer-events.DLT`; nothing reads or alerts on that topic yet.
- The event contract (`TransferEvent`) is duplicated in transaction-service and notification-service on purpose (no
  shared library); a contract test between them does not exist yet.
- Notifications are stored and logged, not actually sent by email or push.
