#!/usr/bin/env bash
# Smoke test for the gateway: run it against the RUNNING Docker stack (docker compose up -d --wait).
#
#   scripts/smoke-gateway.sh                    # default http://localhost:8000
#   scripts/smoke-gateway.sh http://my-host:8000
#
# Checks, through nginx only: the config is valid, the UI loads, every service answers on its
# /api/... route, and the live SSE stream delivers a new payment's events WHILE the stream is still
# open. (It does not prove proxy buffering is off: nginx 1.27 forwarded these small events promptly
# even with buffering on. Buffering is switched off in nginx.conf because that is nginx's advice for
# streams; this test only proves the stream works end to end.)
set -euo pipefail

base="${1:-http://localhost:8000}"
fail=0
ok()  { echo "  PASS  $1"; }
bad() { echo "  FAIL  $1"; fail=1; }

echo "Gateway smoke test against $base"

# 0. nginx accepts its own config (only possible when run next to the compose file).
if docker compose exec -T web nginx -t >/dev/null 2>&1; then ok "nginx -t: config is valid"; else bad "nginx -t: config is valid"; fi

# 1. The UI itself.
page=$(curl -fsS "$base/") && grep -q "<title>PaySwitch demo</title>" <<<"$page" && ok "UI page loads" || bad "UI page loads"
curl -fsS -o /dev/null "$base/app.js" && ok "UI script loads" || bad "UI script loads"

# 2. One call per upstream.
for route in /api/psp/actuator/health /api/switch/actuator/health /api/banka/admin/summary \
             /api/bankb/admin/summary /api/bankc/admin/summary; do
    curl -fsS -o /dev/null "$base$route" && ok "GET $route" || bad "GET $route"
done
summary=$(curl -fsS "$base/api/switch/admin/money-summary" || true)
grep -q '"total"' <<<"$summary" && ok "money summary has a TOTAL" || bad "money summary has a TOTAL"

# 3. SSE through nginx: open the stream, pay while it is open, look for the payment in it.
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

if [[ $fail -eq 0 ]]; then echo "ALL PASS"; else echo "SOME CHECKS FAILED"; fi
exit $fail
