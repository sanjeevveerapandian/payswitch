package com.payswitch.e2e;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.payswitch.common.bank.BalanceResponse;
import com.payswitch.common.bank.BankResponse;
import com.payswitch.common.bank.DeclineReason;
import com.payswitch.common.bank.StatusResponse;
import com.payswitch.common.bank.TxnStatus;
import com.payswitch.common.pay.RespPay;
import com.payswitch.common.pay.TxnDetails;
import com.payswitch.switchservice.recon.ReconReport;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/**
 * Reconciliation against real banks. Every test reconciles a window holding only its own
 * payments, and after every test the total money across both banks is unchanged.
 */
class ReconEndToEndTest {

    private static final long RS_200 = 20_000;
    private static final PaymentSystem system = PaymentSystem.shared();
    private static final RestClient http = RestClient.create();

    private Money before;
    private long raviBefore;
    private long anuBefore;
    private Instant windowFrom;

    @BeforeEach
    void start() {
        system.chaosOff();
        before = Money.snapshot(system);
        raviBefore = ravi();
        anuBefore = anu();
        windowFrom = dbNow();
    }

    @AfterEach
    void moneyIsConserved() {
        system.chaosOff();
        assertEquals(before.total(), Money.snapshot(system).total(), "money was created or destroyed");
        system.auditBothBanks();
    }

    // ---------------------------------------------------------------------------------------

    @Test
    void cleanPaymentsAreAllMatched() {
        List<String> ids = List.of(
                pay("ravi@banka", "anu@bankb", "200.00").txnId(),          // SUCCESS
                pay("kiran@bankb", "meena@banka", "150.50").txnId(),       // SUCCESS, the other way
                pay("meena@banka", "anu@bankb", "99999").txnId(),          // FAILED: not enough money
                pay("ravi@banka", "ghost@bankb", "200").txnId(),           // REVERSED: no such payee
                pay("ravi@banka", "anu@nobank", "200").txnId());           // FAILED: unknown bank
        Instant windowTo = dbNow();
        waitPastCutoff();

        ReconReport report = recon(windowFrom, windowTo);

        assertEquals("COMPLETED", report.status());
        assertEquals(5, report.counts().get("MATCHED"), report.toString());
        assertEquals(0, report.counts().get("SKIPPED_TOO_NEW"));
        assertTrue(report.exceptions().isEmpty(), report.exceptions().toString());
        assertEquals(report, http.get().uri(system.switchUrl() + "/admin/recon/runs/" + report.runId())
                .retrieve().body(ReconReport.class));
        assertEquals(5, ids.size());
    }

    /** Anu's bank was unreachable, but the credit DID land. Recon finds it: SUCCESS, no money moves. */
    @Test
    void deemedApprovedWhoseCreditHappenedBecomesSuccessWithoutMovingMoney() {
        system.chaos(system.bankBUrl(), Map.of("dropResponseRate", 1.0, "statusErrorRate", 1.0));
        String id = pay("ravi@banka", "anu@bankb", "200.00").txnId();
        system.chaosOff();
        assertEquals("DEEMED_APPROVED", switchState(id));
        Instant windowTo = dbNow();
        waitPastCutoff();
        Money beforeRecon = Money.snapshot(system);

        ReconReport report = recon(windowFrom, windowTo);

        assertEquals(1, report.counts().get("RESOLVED_DEEMED"));
        assertEquals("SUCCESS", switchState(id));
        assertTrue(reasons(id).contains("RECON_CREDIT_FOUND"));
        assertEquals(beforeRecon, Money.snapshot(system));                  // recon moved nothing
        // Where is the Rs 200? With Anu.
        assertEquals(raviBefore - RS_200, ravi());
        assertEquals(anuBefore + RS_200, anu());
    }

    /**
     * The credit never happened. Recon fences it at Bank B FIRST, then refunds Ravi. A late copy of
     * the credit arriving afterwards is refused.
     */
    @Test
    void deemedApprovedWhoseCreditNeverHappenedIsFencedThenReversed() {
        system.chaos(system.bankBUrl(), Map.of("errorRate", 1.0, "statusErrorRate", 1.0));
        String id = pay("ravi@banka", "anu@bankb", "200.00").txnId();
        system.chaosOff();
        assertEquals("DEEMED_APPROVED", switchState(id));
        Instant windowTo = dbNow();
        waitPastCutoff();

        ReconReport report = recon(windowFrom, windowTo);

        assertEquals(1, report.counts().get("RESOLVED_DEEMED"));
        assertEquals("REVERSED", switchState(id));
        assertEquals(TxnStatus.FAILED, bankStatus(system.bankBUrl(), id, "CREDIT"));   // the fence
        BankResponse lateCredit = creditDirect(id);
        assertEquals(DeclineReason.FENCED, lateCredit.reason());
        // Where is the Rs 200? Back with Ravi; Anu never got it.
        assertEquals(raviBefore, ravi());
        assertEquals(anuBefore, anu());
        assertEquals(before, Money.snapshot(system));
    }

    @Test
    void aPaymentNewerThanTheCutoffIsSkippedThenPickedUpByALaterRun() {
        String id = pay("ravi@banka", "anu@bankb", "200.00").txnId();
        Instant windowTo = dbNow();

        ReconReport tooSoon = recon(windowFrom, windowTo);                  // straight away

        assertEquals("PARTIAL", tooSoon.status());
        assertEquals(1, tooSoon.counts().get("SKIPPED_TOO_NEW"));
        assertEquals(0, tooSoon.counts().get("MATCHED"));
        assertTrue(tooSoon.exceptions().isEmpty(), "its bank entries must not look BANK_ONLY");

        waitPastCutoff();
        ReconReport later = recon(windowFrom, windowTo);                    // same window, later

        assertEquals("COMPLETED", later.status());
        assertEquals(1, later.counts().get("MATCHED"));
        assertEquals(0, later.counts().get("SKIPPED_TOO_NEW"));
        assertEquals(tooSoon.runId(), later.runId());
        assertEquals("SUCCESS", switchState(id));
    }

    /**
     * Three bugs injected on purpose, straight into the banks:
     * <ul>
     *   <li>SWITCH_ONLY: Bank B "loses" its record of a credit;</li>
     *   <li>AMOUNT_MISMATCH: Bank B's books say it credited Rs 199, not Rs 200 (kept balanced, so
     *       Bank B's own audit cannot see anything wrong: only comparing with the switch can);</li>
     *   <li>BANK_ONLY: Bank A debits Ravi for a txnId the switch never sent.</li>
     * </ul>
     * Each must be reported for manual review, and recon must not move any money for any of them.
     */
    @Test
    void injectedBugsAreReportedAndNothingIsDoneAutomatically() {
        String lostCredit = pay("ravi@banka", "anu@bankb", "200.00").txnId();
        String wrongAmount = pay("ravi@banka", "anu@bankb", "200.00").txnId();
        String rogue = "ROGUE-" + UUID.randomUUID();
        debitDirect(rogue);
        Instant windowTo = dbNow();

        system.bankBDb().sql("DELETE FROM processed_requests WHERE txn_id = :t AND op_type = 'CREDIT'")
                .param("t", lostCredit).update();
        shrinkCreditAtBankB(wrongAmount, 100);

        waitPastCutoff();
        Money beforeRecon = Money.snapshot(system);

        ReconReport report = recon(windowFrom, windowTo);

        Map<String, String> found = report.exceptions().stream()
                .collect(Collectors.toMap(ReconReport.Item::txnId, ReconReport.Item::category));
        assertEquals(Map.of(lostCredit, "SWITCH_ONLY", wrongAmount, "AMOUNT_MISMATCH", rogue, "BANK_ONLY"), found);
        report.exceptions().forEach(i -> assertTrue(i.actionTaken().startsWith("NONE"), i.toString()));
        // No automatic action of any kind:
        assertEquals(beforeRecon, Money.snapshot(system));
        assertEquals("SUCCESS", switchState(lostCredit));
        assertEquals("SUCCESS", switchState(wrongAmount));
        assertEquals(TxnStatus.NOT_FOUND, bankStatus(system.bankAUrl(), lostCredit, "REVERSE"));
        assertEquals(TxnStatus.NOT_FOUND, bankStatus(system.bankAUrl(), rogue, "REVERSE"));
    }

    @Test
    void theSameWindowTwiceGivesTheSameReportAndMovesMoneyAtMostOnce() {
        pay("ravi@banka", "anu@bankb", "200.00");
        system.chaos(system.bankBUrl(), Map.of("errorRate", 1.0, "statusErrorRate", 1.0));
        String deemed = pay("ravi@banka", "anu@bankb", "200.00").txnId();
        system.chaosOff();
        Instant windowTo = dbNow();
        waitPastCutoff();

        ReconReport first = recon(windowFrom, windowTo);
        ReconReport second = recon(windowFrom, windowTo);

        assertEquals(first, second);
        assertEquals(1, first.counts().get("MATCHED"));
        assertEquals(1, first.counts().get("RESOLVED_DEEMED"));
        // The deemed payment was refunded exactly once: one debit + one reversal at Bank A.
        assertEquals(2, system.bankADb().sql("SELECT COUNT(*) FROM journal_entries WHERE txn_id = :t")
                .param("t", deemed).query(Integer.class).single());
        // Where is the money? The first Rs 200 is with Anu; the second is back with Ravi.
        assertEquals(raviBefore - RS_200, ravi());
        assertEquals(anuBefore + RS_200, anu());
    }

    // ---------------------------------------------------------------------------------------

    /**
     * A bank bug that keeps the books balanced: the credit entry for {@code txnId} now says
     * {@code paise} less went to Anu (and so {@code paise} less left settlement). Bank B's own audit
     * still passes; only the comparison with the switch can catch it.
     */
    private static void shrinkCreditAtBankB(String txnId, long paise) {
        var db = system.bankBDb();
        String entry = db.sql("SELECT id FROM journal_entries WHERE txn_id = :t AND op_type = 'CREDIT'")
                .param("t", txnId).query(String.class).single();
        db.sql("UPDATE entry_lines SET amount = amount - :p WHERE journal_entry_id = :e AND amount > 0")
                .param("p", paise).param("e", entry).update();
        db.sql("UPDATE entry_lines SET amount = amount + :p WHERE journal_entry_id = :e AND amount < 0")
                .param("p", paise).param("e", entry).update();
        db.sql("UPDATE accounts SET raw_balance = raw_balance - :p WHERE id = 'SWITCH_SETTLEMENT'")
                .param("p", paise).update();
        db.sql("UPDATE accounts SET raw_balance = raw_balance + :p WHERE vpa = 'anu@bankb'")
                .param("p", paise).update();
    }

    private static Instant dbNow() {
        return system.bankADb().sql("SELECT clock_timestamp()").query(OffsetDateTime.class).single().toInstant();
    }

    /** Reconciliation ignores payments younger than its cutoff; wait until ours are older. */
    private static void waitPastCutoff() {
        try {
            Thread.sleep(PaymentSystem.RECON_CUTOFF.toMillis() + 300);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static ReconReport recon(Instant from, Instant to) {
        return http.post().uri(system.switchUrl() + "/admin/recon/run?from={f}&to={t}", from, to.plusMillis(1))
                .retrieve().body(ReconReport.class);
    }

    private static RespPay pay(String payer, String payee, String rupees) {
        return http.post().uri(system.pspUrl() + "/pay")
                .body(Map.of("payerVpa", payer, "payeeVpa", payee, "amount", rupees))
                .retrieve().body(RespPay.class);
    }

    private static BankResponse debitDirect(String txnId) {
        return http.post().uri(system.bankAUrl() + "/debit")
                .body(Map.of("txnId", txnId, "vpa", "ravi@banka", "amount", RS_200))
                .retrieve().body(BankResponse.class);
    }

    private static BankResponse creditDirect(String txnId) {
        return http.post().uri(system.bankBUrl() + "/credit")
                .body(Map.of("txnId", txnId, "vpa", "anu@bankb", "amount", RS_200))
                .retrieve().body(BankResponse.class);
    }

    private static long ravi() {
        return http.get().uri(system.bankAUrl() + "/accounts/ravi@banka/balance")
                .retrieve().body(BalanceResponse.class).balance();
    }

    private static long anu() {
        return http.get().uri(system.bankBUrl() + "/accounts/anu@bankb/balance")
                .retrieve().body(BalanceResponse.class).balance();
    }

    private static TxnStatus bankStatus(String bankUrl, String txnId, String op) {
        return http.get().uri(bankUrl + "/status/" + txnId + "?op=" + op)
                .retrieve().body(StatusResponse.class).status();
    }

    private static TxnDetails details(String txnId) {
        return http.get().uri(system.switchUrl() + "/txns/" + txnId).retrieve().body(TxnDetails.class);
    }

    private static String switchState(String txnId) {
        return details(txnId).state();
    }

    private static List<String> reasons(String txnId) {
        return details(txnId).events().stream().map(e -> e.reason()).toList();
    }
}
