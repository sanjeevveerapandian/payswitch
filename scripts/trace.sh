#!/usr/bin/env bash
# Shows ONE payment's log lines from every container, merged into one timeline.
#
#   scripts/trace.sh TXN3F2A9C0E5B7D4A1F8E6C2B9D0A7F4E31          readable table
#   scripts/trace.sh TXN3F2A9C0E5B7D4A1F8E6C2B9D0A7F4E31 --raw    the JSON lines themselves
#
# Works because every service logs one JSON object per line, and every line written while working
# on a payment has a "txnId" field (the X-Txn-Id header carries it PSP -> switch -> bank).
# With the load-test override, run it with COMPOSE_FILE=docker-compose.yml:docker-compose.loadtest.yml
set -euo pipefail

txn="${1:-}"
if [[ ! "$txn" =~ ^[A-Za-z0-9_-]{1,64}$ ]]; then
    echo "usage: scripts/trace.sh <txnId> [--raw]" >&2
    exit 2
fi
mode="${2:-}"

cd "$(dirname "$0")/.."

# 1. Every log line of the five services, without docker's "switch-1 | " prefix.
# 2. Only this payment's lines (an exact JSON field match, so TXN1 does not match TXN12).
# 3. Sort by @timestamp. Java prints a variable number of fraction digits (".5" vs ".5123"), which
#    would sort wrongly as text, so each line gets a sort key with the fraction padded to 9 digits.
#    All containers log in UTC, so the times are comparable.
lines=$(docker compose logs --no-color --no-log-prefix psp switch bank-a bank-b bank-c 2>/dev/null \
    | grep -F "\"txnId\":\"$txn\"" \
    | awk '{
        start = index($0, "\"@timestamp\":\"") + 14
        ts = substr($0, start, 40); ts = substr(ts, 1, index(ts, "\"") - 1)
        secs = ts; frac = ""
        dot = index(ts, ".")
        if (dot > 0) {
            secs = substr(ts, 1, dot - 1)
            rest = substr(ts, dot + 1)
            match(rest, /^[0-9]+/); frac = substr(rest, 1, RLENGTH)
        } else {
            secs = substr(ts, 1, 19)
        }
        while (length(frac) < 9) frac = frac "0"
        print secs "." frac "\t" $0
      }' \
    | sort | cut -f2- || true)

if [[ -z "$lines" ]]; then
    echo "No log lines with txnId $txn. Is the system running (docker compose ps)? Wrong txnId?" >&2
    exit 1
fi

if [[ "$mode" == "--raw" ]]; then
    printf '%s\n' "$lines"
    exit 0
fi

# Readable: time, service, level, state (if the line has one), message.
printf '%s\n' "$lines" | awk '
    # Value of a "name":"value" string field, with \" escapes inside the value handled.
    function field(line, name,    key, i, c, out) {
        key = "\"" name "\":\""
        i = index(line, key)
        if (i == 0) return ""
        i += length(key); out = ""
        while (i <= length(line)) {
            c = substr(line, i, 1)
            if (c == "\\") { out = out substr(line, i + 1, 1); i += 2; continue }
            if (c == "\"") break
            out = out c; i++
        }
        return out
    }
    {
        t = field($0, "@timestamp"); sub(/^[0-9-]+T/, "", t)
        printf "%-16.16s  %-22s  %-5s  %-15s  %s\n", t, field($0, "service"), field($0, "level"),
               field($0, "state"), field($0, "message")
    }'
