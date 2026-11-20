package com.payswitch.load.audit;

import com.payswitch.load.Rupees;
import com.payswitch.load.audit.Books.BankBooks;
import com.payswitch.load.audit.Books.Customer;
import com.payswitch.load.audit.Books.SwitchTxn;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The money checks, as pure functions over {@link Books}. Each one looks at the system from a
 * DIFFERENT angle, so one bug cannot easily hide from all of them:
 *
 * <ol>
 *   <li>the grand total (nothing created or destroyed),</li>
 *   <li>the switch's states (nothing left unfinished),</li>
 *   <li>the banks' journals per txnId (nothing done twice),</li>
 *   <li>switch state vs bank journals per payment (each outcome matches the money),</li>
 *   <li>per customer (each person's balance is explained by the switch's final story),</li>
 *   <li>settlement and the central bank (inter-bank money squared up),</li>
 *   <li>reconciliation (no disagreement left for a human).</li>
 * </ol>
 */
public final class MoneyChecks {

    /** The states a payment may end in. Anything else is unfinished. */
    public static final Set<String> TERMINAL = Set.of("SUCCESS", "FAILED", "REVERSED");
    public static final Set<String> RECON_OK = Set.of("MATCHED", "RESOLVED_DEEMED");

    private MoneyChecks() {
    }

    /**
     * @param expectedTotal the total customer money measured before the run, if known. Without it,
     *                      check 1 compares with the total of every OPENING deposit, which is the
     *                      only way money ever enters a customer account.
     */
    public static List<CheckResult> run(Books books, OptionalLong expectedTotal) {
        return List.of(
                totalMoney(books, expectedTotal),
                nothingUnfinished(books),
                nothingDoneTwice(books),
                outcomesMatchTheMoney(books),
                everyCustomerExplained(books),
                settlementSquaredUp(books),
                reconClean(books));
    }

    // 1 -------------------------------------------------------------------------------------------

    static CheckResult totalMoney(Books books, OptionalLong expectedTotal) {
        long now = 0;
        long opening = 0;
        for (BankBooks bank : books.banks()) {
            for (Customer c : bank.customers()) {
                now += c.balance();
                opening += c.opening();
            }
        }
        long expected = expectedTotal.orElse(opening);
        List<String> offenders = new ArrayList<>();
        if (now != expected) {
            offenders.add("TOTAL: customers hold " + now + " paise, expected " + expected
                    + " (difference " + (now - expected) + ")");
        }
        if (expectedTotal.isPresent() && expectedTotal.getAsLong() != opening) {
            offenders.add("TOTAL: measured starting total " + expectedTotal.getAsLong()
                    + " differs from the opening deposits " + opening);
        }
        return CheckResult.of(1, "total customer money unchanged",
                Rupees.rs(now) + " now, " + Rupees.rs(expected) + " at the start", offenders);
    }

    // 2 -------------------------------------------------------------------------------------------

    static CheckResult nothingUnfinished(Books books) {
        List<String> offenders = new ArrayList<>();
        Map<String, Integer> byState = new TreeMap<>();
        for (SwitchTxn t : books.txns()) {
            if (!TERMINAL.contains(t.state())) {
                offenders.add(t.txnId());
                byState.merge(t.state(), 1, Integer::sum);
            }
        }
        return CheckResult.of(2, "no payment left unfinished",
                offenders.isEmpty() ? "0 in DEBIT_SENT / CREDIT_SENT / REVERSAL_SENT / DEEMED_APPROVED (or any other "
                        + "non-final state)" : offenders.size() + " unfinished: " + byState, offenders);
    }

    // 3 -------------------------------------------------------------------------------------------

    /**
     * Across ALL banks, per txnId: at most one DEBIT, one CREDIT and one REVERSE, and the
     * "effective" debit (debits minus reversals) is 0 or 1. A reversal without a debit, or two
     * debits, means money moved twice.
     */
    static CheckResult nothingDoneTwice(Books books) {
        Map<String, Map<String, Integer>> total = new HashMap<>();
        for (BankBooks bank : books.banks()) {
            bank.opCounts().forEach((txnId, ops) -> ops.forEach((op, n) ->
                    total.computeIfAbsent(txnId, k -> new HashMap<>()).merge(op, n, Integer::sum)));
        }
        List<String> offenders = new ArrayList<>();
        total.forEach((txnId, ops) -> {
            int debits = ops.getOrDefault("DEBIT", 0);
            int credits = ops.getOrDefault("CREDIT", 0);
            int reverses = ops.getOrDefault("REVERSE", 0);
            int effectiveDebits = debits - reverses;
            if (debits > 1 || credits > 1 || reverses > 1 || effectiveDebits < 0 || effectiveDebits > 1) {
                offenders.add(txnId);
            }
        });
        offenders.sort(null);
        return CheckResult.of(3, "no txnId debited or credited twice",
                total.size() + " txnIds with bank entries, " + offenders.size() + " with a double movement", offenders);
    }

    // 4 -------------------------------------------------------------------------------------------

    /**
     * For each payment, the money the banks actually moved (summed over every bank) must be
     * exactly what its final state says:
     * <ul>
     *   <li>SUCCESS: payer -amount, payee +amount, nobody else touched;</li>
     *   <li>FAILED / REVERSED: nobody's money changed at all;</li>
     *   <li>money moved for a txnId the switch never heard of: always wrong.</li>
     * </ul>
     */
    static CheckResult outcomesMatchTheMoney(Books books) {
        Map<String, Map<String, Long>> effects = combinedEffects(books);
        List<String> offenders = new ArrayList<>();
        int success = 0;
        int noEffect = 0;
        Set<String> known = new HashSet<>();
        for (SwitchTxn t : books.txns()) {
            known.add(t.txnId());
            Map<String, Long> moved = effects.getOrDefault(t.txnId(), Map.of());
            switch (t.state()) {
                case "SUCCESS" -> {
                    success++;
                    if (!moved.equals(Map.of(t.payerVpa(), -t.amount(), t.payeeVpa(), t.amount()))) {
                        offenders.add(t.txnId());
                    }
                }
                case "FAILED", "REVERSED" -> {
                    noEffect++;
                    if (!moved.isEmpty()) {
                        offenders.add(t.txnId());
                    }
                }
                default -> { }      // unfinished: check 2 already fails it
            }
        }
        for (String txnId : effects.keySet()) {
            if (!known.contains(txnId)) {
                offenders.add(txnId);                     // money moved, switch has no such payment
            }
        }
        offenders.sort(null);
        return CheckResult.of(4, "every outcome matches the money moved",
                success + " SUCCESS with exactly one debit + one credit, " + noEffect
                        + " FAILED/REVERSED with zero net effect; " + offenders.size() + " wrong", offenders);
    }

    // 5 -------------------------------------------------------------------------------------------

    /**
     * For every customer: balance == opening + what the switch says they received - what it says
     * they paid (SUCCESS payments only). Also: nobody is negative.
     */
    static CheckResult everyCustomerExplained(Books books) {
        Map<String, Long> expectedChange = new HashMap<>();
        for (SwitchTxn t : books.txns()) {
            if ("SUCCESS".equals(t.state())) {
                expectedChange.merge(t.payerVpa(), -t.amount(), Long::sum);
                expectedChange.merge(t.payeeVpa(), t.amount(), Long::sum);
            }
        }
        List<String> offenders = new ArrayList<>();
        int customers = 0;
        for (BankBooks bank : books.banks()) {
            for (Customer c : bank.customers()) {
                customers++;
                long expected = c.opening() + expectedChange.getOrDefault(c.vpa(), 0L);
                if (c.balance() != expected || c.balance() < 0) {
                    offenders.add(c.vpa() + " balance " + c.balance() + " expected " + expected
                            + " (opening " + c.opening() + ")");
                }
            }
        }
        return CheckResult.of(5, "every customer's balance explained by the switch",
                customers + " customers checked, " + offenders.size() + " wrong", offenders);
    }

    // 6 -------------------------------------------------------------------------------------------

    static CheckResult settlementSquaredUp(Books books) {
        List<String> offenders = new ArrayList<>();
        Books.CentralBooks central = books.central();
        for (BankBooks bank : books.banks()) {
            if (bank.switchSettlement() != 0) {
                offenders.add(bank.bank() + ": SWITCH_SETTLEMENT is " + bank.switchSettlement() + " after settlement");
            }
            if (bank.rawBalanceSum() != 0 || bank.unbalancedEntries() != 0 || bank.accountsOffJournal() != 0) {
                offenders.add(bank.bank() + ": books do not balance (sum " + bank.rawBalanceSum() + ", "
                        + bank.unbalancedEntries() + " unbalanced entries, " + bank.accountsOffJournal()
                        + " accounts off their journal)");
            }
            // The bank's reserve (its asset) is the central bank's liability to it: raw values are opposite.
            long centralRaw = central.accountRaw().getOrDefault("RESERVE-" + bank.bank(), 0L);
            if (bank.centralReserveRaw() != -centralRaw) {
                offenders.add(bank.bank() + ": CENTRAL_BANK_RESERVE " + bank.centralReserveRaw()
                        + " but the central bank says " + (-centralRaw));
            }
        }
        long centralSum = central.accountRaw().values().stream().mapToLong(Long::longValue).sum();
        if (centralSum != 0 || central.unbalancedEntries() != 0) {
            offenders.add("CENTRAL: accounts sum to " + centralSum + ", " + central.unbalancedEntries()
                    + " unbalanced entries");
        }
        central.accountRaw().forEach((account, raw) -> {
            long lines = central.accountLineSums().getOrDefault(account, 0L);
            if (raw != lines) {
                offenders.add("CENTRAL: " + account + " stored " + raw + " but its lines sum to " + lines);
            }
        });
        central.netSumByCycle().forEach((cycle, sum) -> {
            if (sum != 0) {
                offenders.add("CYCLE " + cycle + ": net positions sum to " + sum);
            }
        });
        if (central.failedCycles() > 0) {
            offenders.add("CYCLES: " + central.failedCycles() + " settlement cycle(s) FAILED");
        }
        return CheckResult.of(6, "settlement squared up",
                "every SWITCH_SETTLEMENT 0, central ledger balanced, " + central.netSumByCycle().size()
                        + " cycle(s) with net positions summing to 0; " + offenders.size() + " problem(s)", offenders);
    }

    // 7 -------------------------------------------------------------------------------------------

    static CheckResult reconClean(Books books) {
        List<String> offenders = new ArrayList<>();
        Map<String, Integer> byCategory = new TreeMap<>();
        books.latestReconCategory().forEach((txnId, category) -> {
            if (!RECON_OK.contains(category)) {
                offenders.add(txnId);
                byCategory.merge(category, 1, Integer::sum);
            }
        });
        offenders.sort(null);
        return CheckResult.of(7, "recon exceptions == 0",
                books.latestReconCategory().size() + " txnIds reconciled, " + offenders.size() + " exceptions"
                        + (byCategory.isEmpty() ? "" : " " + byCategory), offenders);
    }

    // ---------------------------------------------------------------------------------------------

    /** txnId -> VPA -> net change, summed over every bank, zero entries dropped. */
    static Map<String, Map<String, Long>> combinedEffects(Books books) {
        Map<String, Map<String, Long>> all = new HashMap<>();
        for (BankBooks bank : books.banks()) {
            bank.customerEffects().forEach((txnId, perVpa) -> perVpa.forEach((vpa, amount) ->
                    all.computeIfAbsent(txnId, k -> new TreeMap<>()).merge(vpa, amount, Long::sum)));
        }
        all.values().forEach(m -> m.values().removeIf(v -> v == 0));
        all.values().removeIf(Map::isEmpty);
        return all;
    }

    /** The txnIds among the offenders (check 5 lists VPAs, check 1 and 6 list descriptions). */
    public static Set<String> txnIdsIn(List<String> offenders, Books books) {
        Set<String> known = new HashSet<>();
        books.txns().forEach(t -> known.add(t.txnId()));
        books.banks().forEach(b -> known.addAll(b.opCounts().keySet()));
        Set<String> result = new TreeSet<>();
        for (String o : offenders) {
            if (known.contains(o)) {
                result.add(o);
            }
        }
        return result;
    }
}
