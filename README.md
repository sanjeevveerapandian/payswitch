# PaySwitch

A UPI-style payment switch **simulator** in Java 21, Spring Boot 3 and PostgreSQL.
No real money, no real bank or NPCI integration.

```
you -> PSP :8080 -> Switch :8083 -> Bank A :8081   (ravi@banka, meena@banka)
                                 -> Bank B :8082   (anu@bankb, kiran@bankb)
```

| Module | What it does |
|---|---|
| `psp-service` | The payer's app backend. Validates the request, converts rupees to paise, sends it to the switch. |
| `switch-service` | Routes by VPA handle, runs the payment as a state machine, keeps an audit trail of every step. |
| `bank-service` | One app run twice (Bank A, Bank B). Idempotent debit / credit / reverse on a double-entry ledger. |
| `ledger-core` | Pure-Java double-entry rules. No Spring, no database. |
| `common` | Request/response records shared by the services. |
| `e2e-tests` | Starts all four services and sends real payments through them. |

## Start everything

You need Docker Desktop running. Nothing else: Java and Maven run inside the containers.

```bash
docker compose up --build
```

The first build takes a few minutes. It is ready when `psp` reports healthy. Stop with `Ctrl+C`;
`docker compose down -v` also deletes the databases, so the next start has fresh seed balances.

## Make a payment

Ravi (Bank A, opening balance Rs 5,000) pays Rs 200 to Anu's shop (Bank B, Rs 1,000).

bash / Git Bash:

```bash
curl -X POST http://localhost:8080/pay -H "Content-Type: application/json" -d '{"payerVpa":"ravi@banka","payeeVpa":"anu@bankb","amount":"200.00"}'
```

PowerShell:

```powershell
curl.exe --% -X POST http://localhost:8080/pay -H "Content-Type: application/json" -d "{\"payerVpa\":\"ravi@banka\",\"payeeVpa\":\"anu@bankb\",\"amount\":\"200.00\"}"
```

Answer:

```json
{"txnId":"TXN3F2A9C0E5B7D4A1F8E6C2B9D0A7F4E31","state":"SUCCESS","reason":"CREDIT_OK"}
```

Then look at what happened (replace the txnId with yours):

```bash
curl http://localhost:8080/pay/TXN3F2A9C0E5B7D4A1F8E6C2B9D0A7F4E31
curl http://localhost:8083/txns/TXN3F2A9C0E5B7D4A1F8E6C2B9D0A7F4E31
curl http://localhost:8081/accounts/ravi@banka/balance
curl http://localhost:8082/accounts/anu@bankb/balance
```

The first is the payment's status from the PSP. The second is the switch's full history of the
payment, one event per state change. The last two are balances in paise: Ravi `480000`, Anu `120000`.

Other cases to try:

| Request | Result |
|---|---|
| `meena@banka` pays `anu@bankb` `"99999"` | `FAILED`, `DEBIT_DECLINED:INSUFFICIENT_FUNDS`. No money moves. |
| `ravi@banka` pays `ghost@bankb` `"200"` | `REVERSED`. Ravi is debited, Bank B refuses, Ravi is refunded. |
| `ravi@banka` pays `anu@nobank` `"200"` | `FAILED`, `UNKNOWN_HANDLE`. No bank is called. |
| amount `"200.505"` | HTTP 400. Amounts have at most 2 decimals. |

## Run the tests

Docker Desktop must be running (the tests start a real PostgreSQL with Testcontainers).

```bash
mvn test
```
