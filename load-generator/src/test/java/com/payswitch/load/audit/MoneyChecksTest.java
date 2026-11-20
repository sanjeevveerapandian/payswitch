package com.payswitch.load.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.payswitch.load.audit.Books.BankBooks;
import com.payswitch.load.audit.Books.CentralBooks;
import com.payswitch.load.audit.Books.Customer;
import com.payswitch.load.audit.Books.SwitchTxn;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

/**
 * Every check passes on correct books, and each one FAILS when its kind of bug is planted.
 * A check that can never fail proves nothing.
 *
 * <p>The story: Ravi (Bank A, Rs 5,000) pays Anu (Bank B, Rs 1,000) Rs 200: SUCCESS.
 * Then Ravi tries Rs 300 to Anu, the credit is declined, Ravi is refunded: REVERSED.
 * Then a payment that never left the switch: FAILED. Settlement cleared Rs 200 between the banks.
 */
class MoneyChecksTest {

    private static final String OK = "TXN-OK";
    private static final String REV = "TXN-REV";
    private static final String FAIL = "TXN-FAIL";

    /** Builds correct books; each test breaks one thing. */
    private static final class World {
        List<SwitchTxn> txns = new ArrayList<>(List.of(
                new SwitchTxn(OK, "ravi@banka", "anu@bankb", 20000, "SUCCESS"),
                new SwitchTxn(REV, "ravi@banka", "anu@bankb", 30000, "REVERSED"),
                new SwitchTxn(FAIL, "ravi@banka", "anu@bankb", 10000, "FAILED")));
        long raviBalance = 480000;
        long anuBalance = 120000;
        Map<String, Map<String, Integer>> opsA = new HashMap<>(Map.of(
                OK, new HashMap<>(Map.of("DEBIT", 1)),
                REV, new HashMap<>(Map.of("DEBIT", 1, "REVERSE", 1))));
        Map<String, Map<String, Integer>> opsB = new HashMap<>(Map.of(OK, new HashMap<>(Map.of("CREDIT", 1))));
        Map<String, Map<String, Long>> effectsA = new HashMap<>(Map.of(
                OK, new HashMap<>(Map.of("ravi@banka", -20000L)),
                REV, new HashMap<>(Map.of("ravi@banka", 0L))));
        Map<String, Map<String, Long>> effectsB = new HashMap<>(Map.of(OK, new HashMap<>(Map.of("anu@bankb", 20000L))));
        long settlementA = 0;
        long settlementB = 0;
        // Bank A paid Rs 200 net, Bank B received it. Central: RESERVE-BANKA debited (raw +20000).
        long reserveA = -20000;
        long reserveB = 20000;
        Map<String, Long> centralRaw = new HashMap<>(Map.of("RESERVE-BANKA", 20000L, "RESERVE-BANKB", -20000L));
        Map<String, Long> centralLines = new HashMap<>(Map.of("RESERVE-BANKA", 20000L, "RESERVE-BANKB", -20000L));
        Map<Long, Long> netSums = new HashMap<>(Map.of(1L, 0L));
        int failedCycles = 0;
        Map<String, String> recon = new HashMap<>(Map.of(OK, "MATCHED", REV, "MATCHED", FAIL, "MATCHED"));

        Books books() {
            BankBooks a = new BankBooks("BANKA", List.of(new Customer("BANKA", "ravi@banka", raviBalance, 500000)),
                    opsA, effectsA, settlementA, reserveA, 0, 0, 0);
            BankBooks b = new BankBooks("BANKB", List.of(new Customer("BANKB", "anu@bankb", anuBalance, 100000)),
                    opsB, effectsB, settlementB, reserveB, 0, 0, 0);
            return new Books(txns, List.of(a, b), new CentralBooks(centralRaw, centralLines, 0, netSums, failedCycles),
                    recon);
        }

        CheckResult check(int number) {
            return MoneyChecks.run(books(), OptionalLong.empty()).get(number - 1);
        }
    }

    @Test
    void correctBooksPassEveryCheck() {
        List<CheckResult> results = MoneyChecks.run(new World().books(), OptionalLong.of(600000));
        results.forEach(r -> assertTrue(r.pass(), r.line() + " " + r.offenders()));
        assertEquals(7, results.size());
    }

    @Test
    void check1_moneyCreatedOrDestroyed() {
        World w = new World();
        w.anuBalance += 1;                                        // one paisa from nowhere
        assertFalse(w.check(1).pass());
        // And against a measured starting total that disagrees with the books.
        assertFalse(MoneyChecks.run(new World().books(), OptionalLong.of(599999)).get(0).pass());
    }

    @Test
    void check2_paymentLeftUnfinished() {
        World w = new World();
        w.txns.add(new SwitchTxn("TXN-STUCK", "ravi@banka", "anu@bankb", 500, "DEEMED_APPROVED"));
        CheckResult r = w.check(2);
        assertFalse(r.pass());
        assertEquals(List.of("TXN-STUCK"), r.offenders());
    }

    @Test
    void check3_debitedTwiceAcrossBanks() {
        World w = new World();
        w.opsB.put(OK, new HashMap<>(Map.of("CREDIT", 1, "DEBIT", 1)));   // a second debit, at another bank
        assertEquals(List.of(OK), w.check(3).offenders());
    }

    @Test
    void check3_reversalWithoutADebit() {
        World w = new World();
        w.opsA.put(FAIL, new HashMap<>(Map.of("REVERSE", 1)));             // refund of money never taken
        assertEquals(List.of(FAIL), w.check(3).offenders());
    }

    @Test
    void check4_successWithoutTheCredit() {
        World w = new World();
        w.effectsB.remove(OK);                                             // Anu never got the Rs 200
        assertEquals(List.of(OK), w.check(4).offenders());
    }

    @Test
    void check4_successCreditedTheWrongAmount() {
        World w = new World();
        w.effectsB.put(OK, new HashMap<>(Map.of("anu@bankb", 19900L)));
        assertEquals(List.of(OK), w.check(4).offenders());
    }

    @Test
    void check4_reversedButThePayerIsStillOutOfPocket() {
        World w = new World();
        w.effectsA.put(REV, new HashMap<>(Map.of("ravi@banka", -30000L)));
        assertEquals(List.of(REV), w.check(4).offenders());
    }

    @Test
    void check4_moneyMovedForATxnTheSwitchNeverSaw() {
        World w = new World();
        w.effectsA.put("ROGUE-1", new HashMap<>(Map.of("ravi@banka", -500L)));
        assertEquals(List.of("ROGUE-1"), w.check(4).offenders());
    }

    @Test
    void check5_customerBalanceNotExplained() {
        World w = new World();
        w.raviBalance -= 100;
        w.anuBalance += 100;              // the total is still right, so check 1 alone would miss it
        assertTrue(w.check(1).pass());
        CheckResult r = w.check(5);
        assertFalse(r.pass());
        assertEquals(2, r.offenders().size());
    }

    @Test
    void check6_switchSettlementNotCleared() {
        World w = new World();
        w.settlementA = 20000;
        w.settlementB = -20000;
        assertFalse(w.check(6).pass());
    }

    @Test
    void check6_bankReserveDisagreesWithTheCentralBank() {
        World w = new World();
        w.reserveB = 0;                   // the advice never reached Bank B
        assertFalse(w.check(6).pass());
    }

    @Test
    void check6_centralLedgerOrCyclesBroken() {
        World w = new World();
        w.centralRaw.put("RESERVE-BANKC", 5L);
        assertFalse(w.check(6).pass());

        World cycles = new World();
        cycles.netSums.put(2L, 7L);
        assertFalse(cycles.check(6).pass());

        World failed = new World();
        failed.failedCycles = 1;
        assertFalse(failed.check(6).pass());
    }

    @Test
    void check7_reconExceptionLeft() {
        World w = new World();
        w.recon.put(OK, "SWITCH_ONLY");
        w.recon.put(REV, "RESOLVED_DEEMED");                              // fine: recon fixed it
        assertEquals(List.of(OK), w.check(7).offenders());
    }

    @Test
    void offendersThatAreTxnIdsAreFoundForExplaining() {
        Books books = new World().books();
        assertEquals(java.util.Set.of(OK), MoneyChecks.txnIdsIn(List.of(OK, "ravi@banka balance 1 expected 2"), books));
    }
}
