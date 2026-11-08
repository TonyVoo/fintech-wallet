#!/usr/bin/env bash
#
# End-to-end check of a running system, through the API gateway only (the way a real client uses it).
# Works against the local docker-compose stack and, later, against a deployed environment:
#
#   ./scripts/smoke-test.sh                          # http://localhost:8080
#   ./scripts/smoke-test.sh https://wallet.example.com
#
# Needs only bash and curl. Exits with 1 if any check fails.
#
# Note: the gateway allows only about 5 sign-in/register calls per minute per IP (brute-force protection) and this
# script uses 4, so a second run right after the first waits for the bucket to refill (the 429 retry below).

set -u
BASE="${1:-http://localhost:8080}"
JSON='Content-Type: application/json'
SUFFIX="$(date +%s)$RANDOM"
FAILED=0

pass() { printf '  \033[32mPASS\033[0m  %s\n' "$1"; }
fail() { printf '  \033[31mFAIL\033[0m  %s\n' "$1"; FAILED=1; }

# check <description> <expected> <actual>
check() {
  if [ "$2" = "$3" ]; then pass "$1"; else fail "$1 (expected: $2, got: $3)"; fi
}

# field <json> <name>  ->  value of a top-level "name":"value" or "name":number
field() {
  printf '%s' "$1" | sed -nE "s/.*\"$2\":\"?([^\",}]*)\"?.*/\1/p" | head -n1
}

# call <method> <path> [token] [json-body] [extra curl args...]  ->  prints "<body>\n<status>"
# retries a few times when the gateway answers 429 (rate limited)
call() {
  local method="$1" path="$2" token="${3:-}" body="${4:-}"
  shift 4 2>/dev/null || shift $#
  local args=(-s -w '\n%{http_code}' -X "$method" "$BASE$path")
  [ -n "$token" ] && args+=(-H "Authorization: Bearer $token")
  [ -n "$body" ] && args+=(-H "$JSON" -d "$body")
  args+=("$@")
  local out status attempt=0
  while :; do
    out="$(curl "${args[@]}")"
    status="${out##*$'\n'}"
    if [ "$status" = "429" ] && [ "$attempt" -lt 4 ]; then
      attempt=$((attempt + 1)); echo "        (rate limited, waiting 13s then retrying)" >&2; sleep 13; continue
    fi
    printf '%s' "$out"; return
  done
}
body_of()   { printf '%s' "${1%$'\n'*}"; }
status_of() { printf '%s' "${1##*$'\n'}"; }

echo "Smoke test against $BASE"

echo "1. Gateway and services are up"
for i in $(seq 1 60); do
  if curl -s "$BASE/actuator/health" | grep -q '"UP"'; then break; fi
  sleep 2
done
check "gateway health is UP" "200" "$(curl -s -o /dev/null -w '%{http_code}' "$BASE/actuator/health")"

# NOTE for maintainers: request bodies are built in variables and responses are stored before they are parsed.
# macOS ships bash 3.2, which mangles JSON containing "{a,b}" when it is written inside two nested $(...) levels.

# login <email>  ->  prints the access token (empty if the login failed)
login() {
  local payload="{\"email\":\"$1\",\"password\":\"password123\"}"
  local response
  response="$(call POST /api/auth/login "" "$payload")"
  field "$(body_of "$response")" accessToken
}

# create_account <token> <owner name>  ->  prints the new account id
create_account() {
  local payload="{\"ownerName\":\"$2\",\"currency\":\"VND\"}"
  local response
  response="$(call POST /api/accounts "$1" "$payload")"
  field "$(body_of "$response")" id
}

# balance <token> <account id>  ->  prints the balance
balance() {
  local response
  response="$(call GET "/api/accounts/$2" "$1")"
  field "$(body_of "$response")" balance
}

echo "2. Users sign up and sign in"
EMAIL_A="alice-$SUFFIX@example.com"; EMAIL_B="bob-$SUFFIX@example.com"
for e in "$EMAIL_A" "$EMAIL_B"; do
  payload="{\"email\":\"$e\",\"password\":\"password123\"}"
  r="$(call POST /api/auth/register "" "$payload")"
  check "register $e" "201" "$(status_of "$r")"
done
TOKEN_A="$(login "$EMAIL_A")"
TOKEN_B="$(login "$EMAIL_B")"
[ -n "$TOKEN_A" ] && pass "alice received an access token" || fail "alice received an access token"
[ -n "$TOKEN_B" ] && pass "bob received an access token" || fail "bob received an access token"
if [ -z "$TOKEN_A" ] || [ -z "$TOKEN_B" ]; then echo "Cannot continue without tokens."; exit 1; fi

echo "3. Accounts"
ACC_A="$(create_account "$TOKEN_A" Alice)"
ACC_B="$(create_account "$TOKEN_B" Bob)"
if [ -n "$ACC_A" ] && [ -n "$ACC_B" ]; then pass "both accounts created"; else fail "both accounts created"; exit 1; fi
TOPUP='{"amount":1000}'
r="$(call POST "/api/accounts/$ACC_A/deposit" "$TOKEN_A" "$TOPUP")"
check "alice tops up 1000" "200" "$(status_of "$r")"

echo "4. Money transfer with an idempotency key"
KEY="smoke-$SUFFIX"
TRANSFER="{\"fromAccountId\":\"$ACC_A\",\"toAccountId\":\"$ACC_B\",\"amount\":300}"
TOO_MUCH="{\"fromAccountId\":\"$ACC_A\",\"toAccountId\":\"$ACC_B\",\"amount\":99999}"
r="$(call POST /api/transfers "$TOKEN_A" "$TRANSFER" -H "Idempotency-Key: $KEY")"
check "transfer of 300 is completed" "201" "$(status_of "$r")"
check "transfer status" "COMPLETED" "$(field "$(body_of "$r")" status)"
r="$(call POST /api/transfers "$TOKEN_A" "$TRANSFER" -H "Idempotency-Key: $KEY")"
check "retrying with the same key is a replay, not a second transfer" "200" "$(status_of "$r")"
check "alice's balance (1000 - 300, debited once)" "700.0000" "$(balance "$TOKEN_A" "$ACC_A")"
check "bob's balance" "300.0000" "$(balance "$TOKEN_B" "$ACC_B")"
r="$(call POST /api/transfers "$TOKEN_A" "$TOO_MUCH" -H "Idempotency-Key: $KEY-too-much")"
check "transfer above the balance is refused" "422" "$(status_of "$r")"
r="$(call POST /api/transfers "$TOKEN_B" "$TRANSFER" -H "Idempotency-Key: $KEY-theft")"
check "bob cannot spend from alice's account" "404" "$(status_of "$r")"

echo "5. Notification arrives through Kafka (outbox -> topic -> consumer)"
FOUND=""
for i in $(seq 1 30); do
  r="$(call GET /api/notifications "$TOKEN_B")"
  if printf '%s' "$r" | grep -q 'You received 300 VND'; then FOUND=yes; break; fi
  sleep 1
done
if [ -n "$FOUND" ]; then pass "bob was notified: 'You received 300 VND.'"; else fail "bob was notified (no notification within 30s)"; fi

echo "6. What must stay closed"
check "no token -> 401" "401" "$(status_of "$(call GET /api/accounts)")"
internal="$(status_of "$(call GET "/internal/accounts/$ACC_A" "$TOKEN_A")")"
case "$internal" in 401|403|404) pass "/internal is not reachable (HTTP $internal)";; *) fail "/internal is not reachable (HTTP $internal)";; esac
env_status="$(status_of "$(call GET /actuator/env)")"
case "$env_status" in 401|403|404) pass "actuator/env is not reachable (HTTP $env_status)";; *) fail "actuator/env is not reachable (HTTP $env_status)";; esac

echo
if [ "$FAILED" -eq 0 ]; then echo "All checks passed."; else echo "Some checks FAILED."; fi
exit "$FAILED"
