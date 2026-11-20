# 1 lakh payment chaos test: results

Three runs of 1,00,000 payments each, sent through the PSP, then checked by the `MoneyAuditor`
reading every database directly. **All three runs: RESULT: PASS. Not one paisa was created or lost.**

Full reports: [results/run-A.txt](results/run-A.txt), [results/run-B.txt](results/run-B.txt),
[results/run-C.txt](results/run-C.txt). The full logs sit next to them.

## Machine

| | |
|---|---|
| CPU | AMD Ryzen 5 5625U, 6 cores / 12 threads (a laptop) |
| RAM | 15.4 GB |
| OS | Windows 11 Home 10.0.26300 |
| Java | 21.0.12 |
| Docker | Docker Desktop 29.9.3 on WSL2 (kernel 6.6.87.2), 12 CPUs, 7.4 GiB RAM given to Docker |
| Layout | everything on ONE laptop: load generator, PSP, switch, 3 banks and one Postgres container |

## Setup common to all runs

- 900 load customers (300 per bank, `cust001..cust300@banka/bankb/bankc`) plus the 6 demo customers.
  Every 20th load customer is "poor" (Rs 500), the rest have Rs 2,00,000. Starting total: Rs 17,10,40,000.00.
- Seed 42, so the payment plan is the same in all three runs: random payer and payee across all three
  banks (never the same person); amounts mostly small (50% under Rs 100, 1% Rs 20,000 to Rs 1,00,000);
  about 1% are poor customers trying to pay Rs 50,000+ (they must be declined).
- 50 payments in flight at a time (virtual threads + a Semaphore).
- Chaos, when ON, is set on all three banks: declineRate 0.05, errorRate 0.03, dropResponseRate 0.02,
  statusErrorRate 0.05, plus a random delay of 0-300 ms on every bank call.
- Switch timings for the test (`docker-compose.loadtest.yml`): the resolver picks up a payment stuck for
  10 s, checks every 5 s; recon cutoff 10 s. The scheduled recon and settlement jobs are off; the auditor
  runs recon and settlement itself, once, over the whole run.

## The numbers

| | Run A: no chaos | Run B: chaos | Run C: chaos + switch crash at 50% |
|---|---|---|---|
| **Money audit (checks 1-8)** | **PASS** | **PASS** | **PASS** |
| Final SUCCESS | 98,671 | 83,530 | 82,034 |
| Final FAILED | 1,329 | 9,252 | 9,159 |
| Final REVERSED (payer refunded) | 0 | 7,218 | 7,207 |
| Never reached the switch (HTTP 502, no money moved) | 0 | 0 | 1,600 |
| Recon | 1,00,000 MATCHED | 1,00,000 MATCHED | 98,400 MATCHED |
| Recon exceptions | 0 | 0 | 0 |
| TPS (load generator, 50 concurrent) | 179 | 91 | 93 |
| Latency p50 / p95 / p99 | 254 / 558 / 858 ms | 350 / 826 / 5,340 ms | 338 / 797 / 5,324 ms |
| Latency max | 3,480 ms | 8,348 ms | 8,336 ms |
| Load time | 560 s | 1,104 s | 1,075 s |
| Audit time (wait, recon, settle, check) | 326 s | 283 s | 281 s |
| Total runtime | 887 s | 1,389 s | 1,358 s |

### Who had to fix what

| | A | B | C |
|---|---|---|---|
| Bank answer UNKNOWN at least once (timeout, HTTP 500, dropped answer) | 0 | 9,599 | 9,519 |
| Outcome confirmed by a status check | 0 | 3,702 | 3,609 |
| Found in INITIATED after the crash, closed as FAILED | 0 | 0 | 3 |
| Customer told PENDING, finished later by the resolver | 0 | 139 | 154 |
| Reached DEEMED_APPROVED / finished by recon | 0 / 0 | 0 / 0 | 0 / 0 |
| Declined for insufficient funds | 1,329 | 1,231 | 1,236 |

### Settlement (one cycle per run)

| | A | B | C |
|---|---|---|---|
| Gross (sum of all payments) | Rs 13,12,09,759.96 | Rs 11,22,42,383.54 | Rs 10,86,62,215.20 |
| Net actually moved | Rs 9,69,359.68 | Rs 15,99,336.42 | Rs 4,62,092.44 |
| Saved by netting | 99.26% | 98.58% | 99.57% |

Net positions summed to 0 in every run, and every bank's SWITCH_SETTLEMENT account was back to 0.

### Run C: the crash

```
11:16:48.9Z  CRASH: docker compose kill switch
11:16:50.1Z  switch killed; waiting 5000 ms
11:16:55.1Z  RESTART: docker compose start switch
11:16:55.9Z  switch container started (then it boots and runs its startup recovery)
```

While the switch was down, 1,600 payments got HTTP 502 from the PSP: they never reached the switch,
no bank saw them, no money moved. 50 more got HTTP 500: the switch died while they were in flight.
All 50 had been recorded at the switch (98,350 answered + 50 = 98,400 reconciled), and the resolver
finished them after the restart. 3 were caught in INITIATED (created, never sent to a bank) and
closed as FAILED by startup recovery.

## What the numbers mean (honestly)

- **TPS is not a capacity number.** It is what 50 clients waiting for each answer achieve on one laptop
  where the load generator, five services and Postgres share 6 cores. TPS = concurrency / latency:
  50 / 0.28 s is about 179.
- **Chaos latency is mostly injected.** Every bank call waits 0-300 ms on purpose, and a payment makes
  two or more bank calls. The p99 of about 5.3 s is the 5 s bank timeout plus a status check: that is
  the timeout path working, not slowness.
- **Run A has no reversals** because without chaos a credit never fails; its 1,329 FAILED are the
  planned overdraft attempts and poor customers running out of money.
- **TPS halves under chaos** because of the injected delays and timeouts, not because the switch is busy.

## Bugs found by the chaos test

1. **Recon could skip payments and still say COMPLETED (real bug, switch-service).**
   Found by a 2,000-payment smoke run: check 6 failed because SWITCH_SETTLEMENT was not 0.
   157 payments created in the last ~1.5 s before recon started were never reconciled, yet the run said
   COMPLETED with SKIPPED_TOO_NEW = 0.
   *Cause:* recon asked "which payments are old enough?" and later "how many were too new?", each with
   its own `now()`. Payments that were too new for the first query were old enough for the second, so
   they were counted nowhere.
   *Fix:* one horizon per recon run, taken once from the database clock (`clock_timestamp()`, the same
   clock that stamps `created_at`), passed to both queries.
   *Test:* `ReconHorizonTest` makes the gap between the two queries 1.5 s with a 1 s cutoff. It failed
   on the old code (COMPLETED instead of PARTIAL) and passes now.
2. **Seed script overflow (test harness).** `285 * 20000000` is computed as a 32-bit INTEGER in Postgres
   and overflowed. Fixed with `::bigint`. A good reminder of why money is `long`.
3. **Payments sent before the PSP was up (test harness).** The first smoke run counted 650
   "connection refused" as failures. The generator now waits for `/actuator/health`; tested with a fake
   PSP that reports "booting" twice.
4. **The auditor gave up during a long recon (test harness).** See "Laptop sleep" below. Recon calls now
   retry until the deadline, and the auditor waits for the recon cutoff before starting, so one recon
   pass covers the whole run.

## Laptop sleep (why some runs were repeated)

The first B and C runs (kept in [results/standby-affected/](results/standby-affected/)) also PASSED every
money check, but Windows Modern Standby paused the whole machine twice (Kernel-Power events 506/507 in
the Windows System log, about 20 minutes each). Their TPS and latency (max 703 s) are meaningless.
The first run A lost its audit to the same sleep. All three were rerun with the machine kept awake;
the tables above are from the reruns only.

## Known limitation found on the way

Recon over about 1 lakh payments takes about 4.5 minutes. Most of it is the banks paging their recon
entries with `OFFSET`, which gets slower page by page (reading page N skips N-1 pages first).
Keyset pagination (`WHERE id > :lastId ORDER BY id LIMIT n`) would fix this. Not changed in this phase.

## What this proves, and what it does not

**Proves:** for these 3 x 1,00,000 seeded payments, with timeouts, HTTP 500s, dropped answers, failing
status checks, random delays and a hard kill of the switch, every paisa ended in exactly one place:
the total never changed, nothing was debited or credited twice, every customer's balance is exactly
explained by the switch's final states, every bank agreed with the switch in recon, and settlement
squared every bank to zero. No customer was told a final answer that later changed.

**Does not prove:**
- correctness for other seeds, other timings or much higher concurrency (it is a test, not a proof
  over all executions);
- anything about a bank or Postgres crashing, disk loss or real network partitions (only the switch was
  killed, and the network is a laptop's loopback);
- two switch instances racing on the same payments at this scale (one switch ran here);
- real-world performance: a simulated bank, one machine, no TLS, no real NPCI or bank latency.
