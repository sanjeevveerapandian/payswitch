package com.payswitch.e2e;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.payswitch.common.pay.RespPay;
import com.payswitch.common.pay.TxnDetails;
import com.payswitch.switchservice.recon.ReconReport;
import com.payswitch.switchservice.settlement.CentralLedger;
import com.payswitch.switchservice.settlement.SettlementItem;
import com.payswitch.switchservice.settlement.SettlementReport;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.client.RestClient;

/**
 * Phase 9: three real banks, real recon, and settlement cycles closed over HTTP.
 *
 * <p>Other e2e classes share these databases, so a cycle may also hold their reconciled payments.
 * The tests therefore check rules that hold whatever else is in the cycle, plus the exact fate
 * of their own payments.
 *
 * <p>After every test: money is conserved, every bank's books (and the central bank's) balance,
 * each bank's CENTRAL_BANK_RESERVE equals its account at the central bank, and each bank's
 * SWITCH_SETTLEMENT holds exactly its payments that are not settled yet.
 */
class SettlementEndToEndTest {

    private static final PaymentSystem system = PaymentSystem.shared();
    private static final RestClient http = RestClient.create();

    private Money before;

    @BeforeEach
    void start() {
        system.chaosOff();
        before = Money.snapshot(system);
    }

    @AfterEach
    void booksAgreeEverywhere() {
        system.chaosOff();
        assertEquals(before.total(), Money.snapshot(system).total(), "money was created or destroyed");
        system.auditAllBanks();
        system.switchBean(CentralLedger.class).audit();
        Map<String, Long> central = centralReserves();
        system.bankDbs().forEach((bank, db) ->
                assertEquals(central.getOrDefault(bank, 0L), reserve(db), bank + " reserve vs central bank"));
        assertSwitchSettlementIsExactlyTheUnsettledPayments();
    }

    // ---------------------------------------------------------------------------------------

    /** Ravi (A) -> Anu (B) Rs 100, Anu (B) -> Kumar (C) Rs 60, Kumar (C) -> Ravi (A) Rs 40. */
    @Test
    void threeBanksSettleOnlyTheirNetPositionsAndCustomersAreUntouched() {
        Instant from = dbNow();
        Set<String> mine = Set.of(
                pay("ravi@banka", "anu@bankb", "100.00").txnId(),
                pay("anu@bankb", "kumar@bankc", "60.00").txnId(),
                pay("kumar@bankc", "ravi@banka", "40.00").txnId());
        Instant to = dbNow();
        waitPastCutoff();
        assertEquals(3, recon(from, to).counts().get("MATCHED"));
        long customersBefore = Money.snapshot(system).customers();
        Map<String, Long> reservesBefore = bankReserves();

        SettlementReport r = close(null);

        assertEquals("SETTLED", r.status());
        assertTrue(itemIds(r).containsAll(mine), r.items().toString());
        assertEquals(0, r.netPositionsSum());
        assertTrue(r.netPositions().stream().allMatch(SettlementReport.Position::advised));
        assertEquals(recount(r.items()), positions(r));                  // receives minus pays, per bank
        // Each bank's reserve moved by exactly its net position, and nothing else did.
        Map<String, Long> reservesAfter = bankReserves();
        r.netPositions().forEach(p -> assertEquals(p.netAmount(),
                reservesAfter.get(p.bankCode()) - reservesBefore.get(p.bankCode()), p.bankCode()));
        assertEquals(customersBefore, Money.snapshot(system).customers(), "settlement touched customer money");

        // Closing or settling the same cycle again changes nothing, anywhere.
        Money settled = Money.snapshot(system);
        Map<String, Long> central = centralReserves();

        assertEquals(r, close(r.cycleId()));
        assertEquals(r, settle(r.cycleId()));

        assertEquals(settled, Money.snapshot(system));
        assertEquals(reservesAfter, bankReserves());
        assertEquals(central, centralReserves());
        system.bankDbs().forEach((bank, db) -> assertTrue(settlementEntries(db, r.cycleId()) <= 1, bank));
    }

    /**
     * A payment that ends DEEMED_APPROVED is not settled with the cycle that closes while it is
     * unclear. Once recon finds Kumar's bank DID credit Priya, it joins the next cycle.
     */
    @Test
    void aDeemedApprovedPaymentWaitsAndSettlesInTheNextCycleOnceResolved() {
        Instant from = dbNow();
        String clean = pay("meena@banka", "kumar@bankc", "25.00").txnId();
        Instant middle = dbNow();
        system.chaos(system.bankCUrl(), Map.of("dropResponseRate", 1.0, "statusErrorRate", 1.0));
        String deemed = pay("ravi@banka", "priya@bankc", "50.00").txnId();
        system.chaosOff();
        Instant to = dbNow();
        assertEquals("DEEMED_APPROVED", switchState(deemed));
        waitPastCutoff();
        recon(from, middle);                                              // only the clean payment

        SettlementReport first = close(null);

        assertTrue(itemIds(first).contains(clean));
        assertFalse(itemIds(first).contains(deemed));
        assertEquals("SETTLED", first.status());
        // Ravi's Rs 50 is still sitting in Bank A's SWITCH_SETTLEMENT, unsettled (checked after
        // every test too). Now recon looks at it: Bank C has the credit, so it becomes SUCCESS.
        assertTrue(unsettledPerBank().get("BANKA").containsKey(deemed));
        ReconReport later = recon(middle, to);
        assertEquals(1, later.counts().get("RESOLVED_DEEMED"));
        assertEquals("SUCCESS", switchState(deemed));

        SettlementReport second = close(null);

        assertTrue(itemIds(second).contains(deemed));
        assertEquals("SETTLED", second.status());
        assertFalse(unsettledPerBank().get("BANKA").containsKey(deemed));
        assertFalse(unsettledPerBank().get("BANKC").containsKey(deemed));
    }

    // ---- the "who owes whom" invariant -------------------------------------------------------

    /**
     * SWITCH_SETTLEMENT is a running balance: every debit, credit and reverse moves it, and every
     * settlement clears what was settled. So, at any moment, it must equal exactly the money of the
     * payments that are NOT settled yet (0 if there are none). Checked at every bank.
     */
    private static void assertSwitchSettlementIsExactlyTheUnsettledPayments() {
        unsettledPerBank().forEach((bank, perTxn) -> {
            long expected = perTxn.values().stream().mapToLong(Long::longValue).sum();
            assertEquals(expected, Money.settlement(system.bankDbs().get(bank)),
                    bank + ": SWITCH_SETTLEMENT is not exactly its unsettled payments");
        });
    }

    /**
     * Bank code -> (txnId -> what that payment left in the bank's SWITCH_SETTLEMENT), for every
     * payment not in a SETTLED cycle. A debit adds (the bank owes the switch), a credit or reverse
     * takes away. Payments whose movements cancel out (debit + reverse) are left out.
     */
    private static Map<String, Map<String, Long>> unsettledPerBank() {
        Set<String> settled = new HashSet<>(system.switchBean(JdbcClient.class).sql("""
                SELECT i.txn_id FROM settlement_items i JOIN settlement_cycles c ON c.id = i.cycle_id
                WHERE c.status = 'SETTLED' AND i.active
                """).query(String.class).list());
        Map<String, Map<String, Long>> result = new TreeMap<>();
        system.bankDbs().forEach((bank, db) -> {
            Map<String, Long> perTxn = new HashMap<>();
            db.sql("""
                    SELECT e.txn_id, SUM(-l.amount) AS owed FROM entry_lines l
                    JOIN journal_entries e ON e.id = l.journal_entry_id
                    WHERE l.account_id = 'SWITCH_SETTLEMENT' AND e.op_type IN ('DEBIT', 'CREDIT', 'REVERSE')
                    GROUP BY e.txn_id
                    """).query(rs -> {
                        if (!settled.contains(rs.getString("txn_id")) && rs.getLong("owed") != 0) {
                            perTxn.put(rs.getString("txn_id"), rs.getLong("owed"));
                        }
                    });
            result.put(bank, perTxn);
        });
        return result;
    }

    // ---------------------------------------------------------------------------------------

    private static Map<String, Long> recount(List<SettlementItem> items) {
        Map<String, Long> nets = new TreeMap<>(Map.of("BANKA", 0L, "BANKB", 0L, "BANKC", 0L));
        for (SettlementItem i : items) {
            nets.merge(i.payerBank(), -i.amount(), Long::sum);
            nets.merge(i.payeeBank(), i.amount(), Long::sum);
        }
        return nets;
    }

    private static Map<String, Long> positions(SettlementReport r) {
        return new TreeMap<>(r.netPositions().stream()
                .collect(Collectors.toMap(SettlementReport.Position::bankCode, SettlementReport.Position::netAmount)));
    }

    private static Set<String> itemIds(SettlementReport r) {
        return r.items().stream().map(SettlementItem::txnId).collect(Collectors.toSet());
    }

    /** CENTRAL_BANK_RESERVE is an ASSET: natural balance = raw balance. */
    private static long reserve(JdbcClient db) {
        return db.sql("SELECT raw_balance FROM accounts WHERE id = 'CENTRAL_BANK_RESERVE'").query(Long.class).single();
    }

    private static Map<String, Long> bankReserves() {
        Map<String, Long> reserves = new TreeMap<>();
        system.bankDbs().forEach((bank, db) -> reserves.put(bank, reserve(db)));
        return reserves;
    }

    private static int settlementEntries(JdbcClient db, long cycleId) {
        return db.sql("SELECT COUNT(*) FROM journal_entries WHERE txn_id = :t AND op_type = 'SETTLEMENT'")
                .param("t", "CYCLE-" + cycleId).query(Integer.class).single();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Long> centralReserves() {
        Map<String, Number> raw = http.get().uri(system.switchUrl() + "/admin/settlement/central/reserves")
                .retrieve().body(Map.class);
        Map<String, Long> reserves = new TreeMap<>();
        raw.forEach((bank, amount) -> reserves.put(bank, amount.longValue()));
        return reserves;
    }

    private static SettlementReport close(Long cycleId) {
        String query = cycleId == null ? "" : "?cycleId=" + cycleId;
        return http.post().uri(system.switchUrl() + "/admin/settlement/close" + query)
                .retrieve().body(SettlementReport.class);
    }

    private static SettlementReport settle(long cycleId) {
        return http.post().uri(system.switchUrl() + "/admin/settlement/cycles/" + cycleId + "/settle")
                .retrieve().body(SettlementReport.class);
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

    private static String switchState(String txnId) {
        return http.get().uri(system.switchUrl() + "/txns/" + txnId).retrieve().body(TxnDetails.class).state();
    }

    private static Instant dbNow() {
        return system.bankADb().sql("SELECT clock_timestamp()").query(OffsetDateTime.class).single().toInstant();
    }

    private static void waitPastCutoff() {
        try {
            Thread.sleep(PaymentSystem.RECON_CUTOFF.toMillis() + 300);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
