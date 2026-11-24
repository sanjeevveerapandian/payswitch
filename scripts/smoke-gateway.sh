#!/usr/bin/env bash
# Smoke test for the gateway: run it against the RUNNING Docker stack (docker compose up -d --wait).
#
#   scripts/smoke-gateway.sh                         # http://localhost:8000, admin / admin
#   ADMIN_USER=admin ADMIN_PASSWORD=... scripts/smoke-gateway.sh https://your-demo.example.com
#
# Checks, through nginx only:
#   - the config is valid, the UI loads, and every response carries the security headers
#   - the public routes answer; every admin route refuses without the password and answers with it
#   - a bank's own endpoints (like /credit) and other private routes are NOT reachable
#   - an oversized body is refused (413)
#   - the live SSE stream delivers a new payment's events WHILE the stream is still open
#   - POST /pay is rate limited (429) after the burst (last, because it uses up this address's allowance)
# (It does not prove proxy buffering is off: nginx 1.27 forwarded these small events promptly even with
# buffering on. Buffering is switched off in nginx.conf because that is nginx's advice for streams.)
set -euo pipefail

base="${1:-http://localhost:8000}"
user="${ADMIN_USER:-admin}"
pass="${ADMIN_PASSWORD:-admin}"
fail=0
ok()  { echo "  PASS  $1"; }
bad() { echo "  FAIL  $1"; fail=1; }
code() { curl -s -o /dev/null -w '%{http_code}' "$@"; }

echo "Gateway smoke test against $base"

# 0. nginx accepts its own config (only possible when run next to the compose file).
if docker compose exec -T web nginx -t >/dev/null 2>&1; then ok "nginx -t: config is valid"; else bad "nginx -t: config is valid"; fi

# 1. The UI itself, with its security headers.
page=$(curl -fsS "$base/") && grep -q "<title>PaySwitch demo</title>" <<<"$page" && ok "UI page loads" || bad "UI page loads"
curl -fsS -o /dev/null "$base/app.js" && ok "UI script loads" || bad "UI script loads"
headers=$(curl -fsS -D - -o /dev/null "$base/")
for h in "Content-Security-Policy: default-src 'self'" "X-Frame-Options: DENY" "X-Content-Type-Options: nosniff"; do
    grep -qi "^$h" <<<"$headers" && ok "header $h" || bad "header $h"
done
grep -qi "^Server: nginx/" <<<"$headers" && bad "the nginx version is hidden" || ok "the nginx version is hidden"

# 2. Public routes.
summary=$(curl -fsS "$base/api/switch/admin/money-summary" || true)
grep -q '"total"' <<<"$summary" && ok "public: money summary has a TOTAL" || bad "public: money summary has a TOTAL"

# 3. Admin routes: 401 without the password, 200 with it.
for route in /api/admin/login /api/admin/psp/actuator/health /api/admin/switch/actuator/health \
             /api/admin/banka/admin/summary /api/admin/bankb/admin/summary /api/admin/bankc/admin/summary \
             /api/admin/banka/admin/chaos /grafana/api/health; do
    [[ $(code "$base$route") == 401 ]] && ok "no password: 401 $route" || bad "no password: 401 $route"
    [[ $(code -u "$user:$pass" "$base$route") == 200 ]] && ok "admin: 200 $route" || bad "admin: 200 $route"
done
[[ $(code -u "$user:wrong-password" "$base/api/admin/login") == 401 ]] && ok "wrong password: 401" || bad "wrong password: 401"

# 4. Private routes stay private, whatever the method.
for route in /api/banka/credit /api/banka/admin/chaos /api/switch/pay /api/switch/admin/recon/run \
             /api/psp/admin/load /api/switch/actuator/prometheus /api/switch/admin/scenario/nope; do
    c=$(code -X POST -H 'Content-Type: application/json' -d '{}' "$base$route")
    [[ $c == 404 ]] && ok "private: 404 POST $route" || bad "private: 404 POST $route (got $c)"
done
c=$(code -X DELETE "$base/api/switch/admin/money-summary")
[[ $c == 403 ]] && ok "public GET-only route refuses DELETE (403)" || bad "public GET-only route refuses DELETE (got $c)"

# 5. Request size limit (16 KB).
big=$(head -c 20000 /dev/zero | tr '\0' 'x')
c=$(code -X POST -H 'Content-Type: application/json' --data-binary "{\"x\":\"$big\"}" "$base/api/psp/pay")
[[ $c == 413 ]] && ok "a 20 KB body is refused (413)" || bad "a 20 KB body is refused (got $c)"

# 6. SSE through nginx: open the stream, pay while it is open, look for the payment in it.
stream=$(mktemp)
curl -sS -N --max-time 5 "$base/api/switch/events/txns" > "$stream" 2>/dev/null &
reader=$!
sleep 1
pay=$(curl -fsS -X POST "$base/api/psp/pay" -H "Content-Type: application/json" \
      -d '{"payerVpa":"ravi@banka","payeeVpa":"anu@bankb","amount":"1.00"}' || true)
txn=$(sed -n 's/.*"txnId":"\([A-Za-z0-9_-]*\)".*/\1/p' <<<"$pay")
[[ -n "$txn" ]] && ok "POST /api/psp/pay -> $txn" || bad "POST /api/psp/pay ($pay)"
wait "$reader" || true                         # curl exits on --max-time: the stream never ends by itself

grep -q "^event:snapshot" "$stream" && ok "SSE: snapshot on connect" || bad "SSE: snapshot on connect"
if [[ -n "$txn" ]] && grep -q "^event:txns" "$stream" && grep -q "$txn" "$stream"; then
    ok "SSE: the new payment's events arrived while the stream was open"
else
    bad "SSE: the new payment's events arrived live"
fi
rm -f "$stream"
[[ $(code "$base/api/psp/pay/$txn") == 200 ]] && ok "public: GET /api/psp/pay/{txnId}" || bad "public: GET /api/psp/pay/{txnId}"
[[ $(code "$base/api/switch/txns/$txn") == 200 ]] && ok "public: GET /api/switch/txns/{txnId}" || bad "public: GET /api/switch/txns/{txnId}"

# 7. Rate limit on /pay: 10 a minute with a burst of 5. Invalid bodies are enough (the PSP answers 400),
# so no money moves; 15 quick requests must hit 429 at least once.
limited=0
for _ in $(seq 1 15); do
    c=$(code -X POST -H 'Content-Type: application/json' -d '{}' "$base/api/psp/pay")
    [[ $c == 429 ]] && limited=1
done
[[ $limited == 1 ]] && ok "POST /pay is rate limited (429)" || bad "POST /pay is rate limited"

if [[ $fail -eq 0 ]]; then echo "ALL PASS"; else echo "SOME CHECKS FAILED"; fi
exit $fail
