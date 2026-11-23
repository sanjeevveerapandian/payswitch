# PaySwitch

A UPI-style payment switch **simulator**: a payer's app, a switch and three banks that move money
between each other safely, even when the network loses messages or a bank stops answering, and
then settle up with each other through a central bank.
Java 21, Spring Boot 3, PostgreSQL, Docker Compose.

> **Disclaimer:** PaySwitch is a learning project and a simulator. It is not affiliated with NPCI,
> UPI or any bank, it does not connect to any real bank or payment network, and it moves no real money.

## Architecture

```mermaid
flowchart LR
    U([Payer's app]) -->|POST /pay<br/>rupees as text| PSP[PSP<br/>:8080]
    PSP -->|ReqPay<br/>paise| SW[Switch<br/>:8083]
    SW -->|debit / reverse<br/>status-check| A[Bank A<br/>:8081]
    SW -->|credit<br/>status-check| B[Bank B<br/>:8082]
    SW --> C[Bank C<br/>:8084]
    SW --- DBS[(switch db<br/>+ central bank ledger)]
    A --- DBA[(banka db)]
    B --- DBB[(bankb db)]
    C --- DBC[(bankc db)]
    subgraph Postgres
        DBS
        DBA
        DBB
        DBC
    end
```

| Service | Job |
|---|---|
| **PSP** | The payer's app backend. Validates the request, turns rupees into paise (the only place that happens), makes a txnId. |
| **Switch** | Finds each bank from the VPA handle (`@banka`, `@bankb`, `@bankc`), runs the payment as a state machine, resolves every unclear bank answer, reconciles with the banks and settles cycles. |
| **Bank A / B / C** | The same app run three times. A double-entry ledger with idempotent debit, credit and reverse. |
| `ledger-core` | The double-entry rules in plain Java, with no Spring or database. |
| `observability` | The txnId filter, header interceptor and MDC helper every service shares. |
| Prometheus + Grafana | Scrape every service's metrics; one provisioned dashboard and three alert rules. |

## A payment's life (the switch's state machine)

```mermaid
stateDiagram-v2
    [*] --> INITIATED
    INITIATED --> DEBIT_SENT
    INITIATED --> FAILED: unknown bank / never sent
    DEBIT_SENT --> DEBITED: bank A: done
    DEBIT_SENT --> FAILED: bank A: no (or fenced)
    DEBIT_SENT --> REVERSAL_SENT: still unknown
    DEBITED --> CREDIT_SENT
    DEBITED --> REVERSAL_SENT
    CREDIT_SENT --> SUCCESS: bank B: done
    CREDIT_SENT --> REVERSAL_SENT: bank B: no (or fenced)
    CREDIT_SENT --> DEEMED_APPROVED: still unknown
    DEEMED_APPROVED --> SUCCESS: checked later: done
    DEEMED_APPROVED --> REVERSAL_SENT: checked later: never happened
    REVERSAL_SENT --> REVERSED: money back
    REVERSAL_SENT --> FAILED: there was no debit
    SUCCESS --> [*]
    FAILED --> [*]
    REVERSED --> [*]
```

Each state is saved **before** the bank is called, so after a crash the database says exactly
which bank to ask about what. Payments that stop moving are picked up by a background job, and
once when the switch starts (crash recovery).

| State | Where is Ravi's Rs 200? | Customer sees |
|---|---|---|
| INITIATED | With Ravi | PENDING |
| DEBIT_SENT | Not known yet: with Ravi, or in Bank A's settlement account | PENDING |
| DEBITED | In transit (Bank A settlement) | PENDING |
| CREDIT_SENT | In transit, or already with Anu | PENDING |
| DEEMED_APPROVED | Believed with Anu; checked again later | PENDING |
| REVERSAL_SENT | In transit, or already back with Ravi | PENDING |
| SUCCESS | With Anu | SUCCESS |
| FAILED | With Ravi (it never left) | FAILED |
| REVERSED | With Ravi (it left and came back) | REVERSED |

## Run it

You only need Docker.

```bash
docker compose up --build -d
```

Ravi (Bank A, starts with Rs 5,000) pays Rs 200 to Anu's shop (Bank B, starts with Rs 1,000):

```bash
curl -X POST http://localhost:8080/pay -H "Content-Type: application/json" \
  -d '{"payerVpa":"ravi@banka","payeeVpa":"anu@bankb","amount":"200.00"}'
```

```json
{"txnId":"TXN3F2A...","state":"SUCCESS","reason":"CREDIT_OK"}
```

On Windows PowerShell, use `curl.exe --%` with the JSON quotes escaped:

```powershell
curl.exe --% -X POST http://localhost:8080/pay -H "Content-Type: application/json" -d "{\"payerVpa\":\"ravi@banka\",\"payeeVpa\":\"anu@bankb\",\"amount\":\"200.00\"}"
```

Look around (balances are in paise):

```bash
curl http://localhost:8080/pay/TXN3F2A...              # the payment's status
curl http://localhost:8083/txns/TXN3F2A...             # every step, with the reason for each
curl http://localhost:8081/accounts/ravi@banka/balance # 480000
curl http://localhost:8082/accounts/anu@bankb/balance  # 120000
```

Bank C (port 8084) has Kumar (`kumar@bankc`, Rs 4,000) and Priya (`priya@bankc`, Rs 2,500).

Stop with `docker compose down`; `docker compose down -v` also resets every balance (and is needed
once after upgrading, so Postgres creates the new `bankc` database).

## Chaos mode: make the banks unreliable

All banks run with the `chaos` profile, so `/admin/chaos` exists. Every rate starts at 0.

| Setting | What the bank does to /debit, /credit, /reverse |
|---|---|
| `declineRate` | answers DECLINED without doing anything |
| `errorRate` | answers HTTP 500 without doing anything |
| `delayMs` | waits this long first |
| `delayJitterMs` | waits a random extra 0..this many ms (so `delayMs` 0 + jitter 300 = random 0-300 ms) |
| `dropResponseRate` | **does** the operation, commits it, then stays silent past the switch's timeout |
| `statusErrorRate` | answers HTTP 500 to `/status-check` |
| `seed` | repeat the same random decisions every time |

Example: Bank A does every debit but never replies. The payment still ends in `SUCCESS`, about
5 seconds later: the switch times out, asks Bank A what happened, finds the debit, and carries on.

```bash
curl -X POST http://localhost:8081/admin/chaos -H "Content-Type: application/json" -d '{"dropResponseRate":1.0}'
curl -X POST http://localhost:8080/pay -H "Content-Type: application/json" \
  -d '{"payerVpa":"ravi@banka","payeeVpa":"anu@bankb","amount":"200.00"}'
curl -X POST http://localhost:8081/admin/chaos -H "Content-Type: application/json" -d '{}'   # chaos off
```

## Settlement: who pays whom at the end of the day

During the day no real money moves between banks. Each bank only records "I owe the switch"
or "the switch owes me" in its `SWITCH_SETTLEMENT` account. Periodically the switch closes a
**settlement cycle** and banks pay only their **net** position (receives minus pays):

| Payments in the cycle | Bank A | Bank B | Bank C |
|---|---|---|---|
| A -> B Rs 10,000 | -10,000 | +10,000 | |
| B -> C Rs 6,000 | | -6,000 | +6,000 |
| C -> A Rs 4,000 | +4,000 | | -4,000 |
| **Net position** | **-6,000** | **+4,000** | **+2,000** |

Gross Rs 20,000, but only Rs 6,000 actually moves (70% saved). The positions always sum to zero;
the switch checks that, and a cycle that does not add up is marked FAILED and never settled.

Only payments that are SUCCESS **and** confirmed by reconciliation join a cycle. DEEMED_APPROVED
payments and reconciliation exceptions wait for a later cycle.

```bash
curl -X POST http://localhost:8083/admin/settlement/close        # close + settle the open cycle
curl http://localhost:8083/admin/settlement/cycles/1             # items, net positions, gross vs net
curl http://localhost:8083/admin/settlement/central/reserves     # each bank at the central bank
```

Settling posts one journal entry in the central bank's ledger (same `ledger-core` rules), then
sends each bank `POST /settlement/advice`, which moves the amount from `SWITCH_SETTLEMENT` to
its `CENTRAL_BANK_RESERVE`. Every step is idempotent by cycle id.

## The 1 lakh payment chaos test

A load generator sends lakhs of payments through the PSP; a money auditor then reads all four
databases directly and checks, to the paisa, that nothing was lost. Results: [RESULTS.md](RESULTS.md).

```bash
mvn -pl load-generator -am install -DskipTests          # builds load-generator/target/*-all.jar
docker compose down -v
docker compose -f docker-compose.yml -f docker-compose.loadtest.yml up --build -d
java -jar load-generator/target/load-generator-0.1.0-SNAPSHOT-all.jar --count 100000 --concurrency 50      --seed 42 --chaos on --crash-switch-at 50
```

| Option | Default | |
|---|---|---|
| `--count` | 100000 | payments to send |
| `--concurrency` | 50 | payments in flight at once |
| `--seed` | 42 | same seed, same payments |
| `--chaos` | off | on: 5% declines, 3% HTTP 500, 2% dropped answers, 5% failed status checks, 0-300 ms delay, on every bank |
| `--crash-switch-at` | none | at this % of payments sent: `docker compose kill switch`, 5 s, `docker compose start switch` |
| `--psp-url` | http://localhost:8080 | |
| `--audit` | on | off: only send, do not audit |
| `--report` | | also write the report to this file |

`docker-compose.loadtest.yml` adds 300 customers per bank (`cust001@banka` .. `cust300@bankc`) and
makes the switch's timers faster so the audit does not wait minutes. The auditor alone, any time:

```bash
java -cp load-generator/target/load-generator-0.1.0-SNAPSHOT-all.jar com.payswitch.load.audit.MoneyAuditor
```

A 5,000 payment version runs automatically in `mvn verify` (`LoadChaosIT`), not in `mvn test`.

## Watching it: metrics, dashboard, alerts and one payment's trail

`docker compose up` also starts Prometheus and Grafana, already set up from files in `monitoring/`:

| What | Where |
|---|---|
| Grafana dashboard (rows: Overview, Latency, Health, Banks, Money) | http://localhost:3000 (opens on it; admin / admin to edit) |
| Prometheus, and the three alert rules | http://localhost:9090, http://localhost:9090/alerts |
| One service's raw metrics | http://localhost:8083/actuator/prometheus (8080, 8081, 8082, 8084 for the others) |

The metrics. Labels only ever take a few fixed values: never a txnId, VPA or amount.

| Metric | Type | Labels |
|---|---|---|
| `payswitch_payments_total` | counter | `final_state` |
| `payswitch_bank_call_seconds` | histogram (p50/p95/p99 in Grafana) | `bank`, `op`, `outcome` |
| `payswitch_txns_in_state`, `payswitch_txns_stuck` | gauge (a DB count every 15 s) | `state` |
| `payswitch_status_checks_total` | counter | `bank`, `result` |
| `payswitch_retries_total` | counter | `bank`, `op` |
| `payswitch_resolver_fixed_total` | counter | `from_state` |
| `payswitch_recon_items_total` | counter | `category` |
| `payswitch_settlement_net_amount_paise` | gauge (last settled cycle) | `bank` |
| `bank_ops_total` | counter | `bank`, `op`, `result` |
| `bank_idempotent_replays_total`, `bank_fenced_rejections_total` | counter | `bank`, `op` |
| `bank_chaos_injected_total` | counter | `bank`, `mode` |
| `psp_requests_total`, `psp_request_seconds` | counter, histogram | `result` |

Alerts: **PaymentsStuck** (any payment stuck for 5 minutes), **ReconExceptions** (any recon item a
human must look at), **BankUnknownRateHigh** (over 10% of calls to one bank get no clear answer
for 2 minutes).

Every log line is one JSON object. The PSP makes the txnId and sends it on as the `X-Txn-Id` header;
the switch passes it to every bank call, and each service puts it into every log line about that
payment. So one command shows a payment's whole story, from every container, in time order:

```bash
scripts/trace.sh TXN3F2A...          # add --raw for the JSON lines
```

```
03:13:01.3717434  switch-service          INFO   INITIATED        Txn TXNF607...: created, BANKC -> BANKA
03:13:01.5290918  bank-service-BANKC      INFO                    DEBIT TXNF607...: SUCCESS
03:13:01.8571167  bank-service-BANKA      WARN                    CHAOS CREDIT TXNF607...: answering HTTP 500, nothing processed
03:13:01.8623188  switch-service          WARN   CREDIT_SENT      BANKA CREDIT TXNF607... -> UNKNOWN (HTTP 500)
03:13:01.9162420  bank-service-BANKA      INFO                    STATUS-CHECK CREDIT TXNF607...: no record, FENCED now
03:13:01.9340175  switch-service          INFO   REVERSAL_SENT    Txn TXNF607...: CREDIT_SENT -> REVERSAL_SENT (CREDIT_DECLINED:FENCED)
03:13:03.4214286  bank-service-BANKC      INFO                    REVERSE TXNF607...: SUCCESS
03:13:03.4404116  switch-service          INFO   REVERSED         Txn TXNF607...: REVERSAL_SENT -> REVERSED (REVERSAL_OK)
03:13:03.4536269  psp-service             INFO                    Txn TXNF607...: switch answered REVERSED (REVERSAL_OK)
```

## Design decisions

| Decision | Why |
|---|---|
| Money is `long` paise; rupees only at the PSP, with `BigDecimal` | `double` cannot hold most decimal amounts exactly. A decimal amount anywhere else is refused. |
| Double-entry ledger: every entry's lines sum to zero | Money can only move, never appear or vanish, and an audit query can prove it. |
| Every bank operation is idempotent by (txnId, operation) | Retries are unavoidable on a network; they must never debit or credit twice. |
| Claim, money and saved answer in ONE database transaction | A crash between them can never leave "money moved but no record", or the reverse. |
| `SELECT ... FOR UPDATE` on account rows, locked in id order | Stops lost updates; one fixed lock order makes deadlocks impossible. |
| A per-txnId advisory lock in the bank | A late request and a status check or reversal for the same txnId can never interleave. |
| Payment = state machine; every change is an audit-trail row | Illegal moves are impossible, and every payment's history can be read back step by step. |
| Optimistic locking (`version`) in the switch | The switch waits seconds for banks; holding a database lock that long would block everyone. |
| State saved BEFORE each bank call | After a crash, the saved state says which bank to ask about what. |
| Every bank answer is SUCCESS, DECLINED or UNKNOWN, decided in one class | A timeout is not a failure. Only a clear answer from the bank counts. |
| Status check with fencing | "No record" becomes a promise the request will never run later, so FAILED is safe to say. |
| Unknown debit, so reverse; unknown credit, so never reverse (DEEMED_APPROVED) | Reversing a debit is safe either way; reversing a credit that did happen would create money. |
| Retries with exponential backoff and jitter | Gives a struggling bank room to recover, and spreads retries so they do not all land at once. |
| Background resolver with `FOR UPDATE SKIP LOCKED` and a lease | Many copies of the switch can run it at once without ever handling the same payment twice. |
| The money auditor reads the databases with SQL, not the services' APIs | An auditor must not trust the code it audits. |
| Metric labels are fixed small sets (state, bank, op), never a txnId | Every label combination is a separate time series; one per payment would sink Prometheus. Find one payment in the logs instead. |
| Counters for final states are bumped only after the database commit | A rolled-back state change must not be counted. |
| The txnId travels as a header and sits in the MDC, set and cleared by a filter | Every log line about a payment can be found by its id, and a reused thread never logs the previous request's id. |
| Multilateral net settlement of reconciled payments only | Far less money moves between banks, and nothing is settled that the banks' own books do not confirm. |

## Tests

```bash
mvn test
```

Needs Docker: the tests start a real PostgreSQL with Testcontainers. `mvn verify` also runs the
5,000 payment chaos test (about 2 minutes). Coverage reports are written
to each module's `target/site/jacoco/index.html`.
