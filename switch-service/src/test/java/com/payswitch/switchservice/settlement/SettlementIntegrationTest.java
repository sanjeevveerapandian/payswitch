package com.payswitch.switchservice.settlement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.payswitch.common.bank.OpType;
import com.payswitch.common.bank.ReconEntry;
import com.payswitch.common.bank.ReconStatus;
import com.payswitch.common.settlement.SettlementAdvice;
import com.payswitch.switchservice.TestcontainersConfig;
import com.payswitch.switchservice.bank.BankCallResult;
import com.payswitch.switchservice.bank.BankClient;
import com.payswitch.switchservice.domain.TxnState;
import com.payswitch.switchservice.metrics.SwitchMetrics;
import com.payswitch.switchservice.recon.BankReconClient;
import com.payswitch.switchservice.recon.ReconRepository;
import com.payswitch.switchservice.recon.ReconciliationService;
import com.payswitch.switchservice.routing.BankRoute;
import com.payswitch.switchservice.service.TransactionService;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * Settlement cycles against a real Postgres. The banks are mocks: their recon books are a map we
 * fill in, and every settlement advice is recorded.
 *
 * <p>Before each test the open cycle is closed and settled once ("drained"), so the cycle a test
 * closes holds only that test's own payments.
 */
@SpringBootTest(properties = {
        "switch.resolution.enabled=false",
        "switch.recon.enabled=false",
        "switch.recon.cutoff=0s",
        "switch.settlement.enabled=false",          // tests close cycles themselves
        "switch.retry.base-delay=10ms",
        "switch.retry.max-attempts=2",
})
@Import(TestcontainersConfig.class)
@ExtendWith(OutputCaptureExtension.class)
class SettlementIntegrationTest {

    private static final long RUPEE = 100;

    @MockitoBean
    private SettlementAdviceClient adviceClient;

    @MockitoBean
    private BankReconClient bankRecon;

    @MockitoBean
    private BankClient banks;

    @MockitoSpyBean
    private NetPositionCalculator calculator;

    @Autowired
    private SettlementService settlement;

    @Autowired
    private CentralLedger central;

    @Autowired
    private TransactionService txns;

    @Autowired
    private ReconciliationService recon;

    @Autowired
    private ReconRepository reconRepo;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private SwitchMetrics metrics;

    /** What each bank's /recon/entries returns: bank code -> entries. */
    private final Map<String, List<ReconEntry>> bankBooks = new ConcurrentHashMap<>();

    @BeforeEach
    void setUp() {
        when(adviceClient.send(any(), any())).thenReturn(true);
        when(bankRecon.fetchAll(any(), any(), any(), anyInt())).thenAnswer(inv ->
                List.copyOf(bankBooks.getOrDefault(((BankRoute) inv.getArgument(0)).code(), List.of())));
        when(banks.statusCheckAndFence(any(), anyString(), any())).thenReturn(BankCallResult.unknown("bank down"));
        settlement.closeAndSettle(null);                       // drain: start every test with an empty cycle
        Mockito.clearInvocations(adviceClient);
    }

    @AfterEach
    void centralBooksStillBalance() {
        reset(calculator);
        central.audit();
    }

    // ---- the examples ----------------------------------------------------------------------

    /** A -> B Rs 10000, B -> C Rs 6000, C -> A Rs 4000. */
    @Test
    void theThreeBankExampleSettlesRs6000InsteadOfRs20000() {
        Instant from = dbNow();
        String ab = paid("ravi@banka", "anu@bankb", 10_000 * RUPEE);
        String bc = paid("anu@bankb", "kumar@bankc", 6_000 * RUPEE);
        String ca = paid("kumar@bankc", "ravi@banka", 4_000 * RUPEE);
        reconcile(from);
        Map<String, Long> reservesBefore = central.reserves();

        SettlementReport r = settlement.closeAndSettle(null);

        assertEquals("SETTLED", r.status());
        assertEquals(Set.of(ab, bc, ca), txnIds(r));
        assertEquals(List.of(
                new SettlementReport.Position("BANKA", -6_000 * RUPEE, true),
                new SettlementReport.Position("BANKB", 4_000 * RUPEE, true),
                new SettlementReport.Position("BANKC", 2_000 * RUPEE, true)), r.netPositions());
        assertEquals(0, r.netPositionsSum());
        assertEquals(new GrossVsNet(20_000 * RUPEE, 6_000 * RUPEE, new BigDecimal("70.00")), r.grossVsNet());
        // The central bank moved exactly the net amounts, in ONE journal entry...
        Map<String, Long> reservesAfter = central.reserves();
        assertEquals(-6_000 * RUPEE, reservesAfter.get("BANKA") - reservesBefore.getOrDefault("BANKA", 0L));
        assertEquals(4_000 * RUPEE, reservesAfter.get("BANKB") - reservesBefore.getOrDefault("BANKB", 0L));
        assertEquals(2_000 * RUPEE, reservesAfter.get("BANKC") - reservesBefore.getOrDefault("BANKC", 0L));
        assertEquals(1, centralEntries(r.cycleId()));
        // ...and each bank was told its own net position.
        verify(adviceClient).send(bank("BANKA"), argThat(a -> a.equals(new SettlementAdvice(r.cycleId(), -600_000))));
        verify(adviceClient).send(bank("BANKB"), argThat(a -> a.equals(new SettlementAdvice(r.cycleId(), 400_000))));
        verify(adviceClient).send(bank("BANKC"), argThat(a -> a.equals(new SettlementAdvice(r.cycleId(), 200_000))));
    }

    /** Phase 11: the "last settlement" gauge, and that a restarted switch reads it back from the database. */
    @Test
    void theLastSettledNetPositionsAreAGaugeThatSurvivesARestart() {
        Instant from = dbNow();
        paid("ravi@banka", "anu@bankb", 500 * RUPEE);
        paid("anu@bankb", "kumar@bankc", 200 * RUPEE);
        reconcile(from);

        settlement.closeAndSettle(null);

        assertEquals(-500 * RUPEE, net("BANKA"));
        assertEquals(300 * RUPEE, net("BANKB"));
        assertEquals(200 * RUPEE, net("BANKC"));

        // A restart starts with empty gauges; pretend they hold nonsense, then run the startup hook.
        metrics.settlementNet("BANKA", 42);
        settlement.publishLastSettledPositions();

        assertEquals(-500 * RUPEE, net("BANKA"), "read back from the last SETTLED cycle in the database");
    }

    private long net(String bank) {
        return (long) metrics.registry().get("payswitch.settlement.net.amount.paise").tag("bank", bank).gauge().value();
    }

    @Test
    void aRandomThousandPaymentCycleAcrossThreeBanksSumsToZero() {
        List<String> handles = List.of("banka", "bankb", "bankc");
        Random random = new Random(7);
        Map<String, Long> expected = new HashMap<>(Map.of("BANKA", 0L, "BANKB", 0L, "BANKC", 0L));
        List<Object[]> rows = new ArrayList<>();
        long gross = 0;
        for (int i = 0; i < 1_000; i++) {
            String payer = handles.get(random.nextInt(3));
            String payee = handles.get(random.nextInt(3));
            long amount = 1 + random.nextInt(10_000_000);
            rows.add(new Object[] {"RND-" + UUID.randomUUID(), "u" + i + "@" + payer, "v" + i + "@" + payee,
                    payer.toUpperCase(), payee.toUpperCase(), amount});
            expected.merge(payer.toUpperCase(), -amount, Long::sum);
            expected.merge(payee.toUpperCase(), amount, Long::sum);
            gross += amount;
        }
        // Straight into the tables: 1,000 SUCCESS payments, each with a MATCHED recon line.
        jdbcTemplate.batchUpdate("""
                INSERT INTO transactions (txn_id, payer_vpa, payee_vpa, payer_bank, payee_bank, amount, state)
                VALUES (?, ?, ?, ?, ?, ?, 'SUCCESS')
                """, rows);
        Instant now = dbNow();
        long runId = reconRepo.findOrCreateRun(now.minusSeconds(3600), now);
        jdbcTemplate.batchUpdate("""
                INSERT INTO recon_items (run_id, txn_id, category, switch_state, bank_view, action_taken)
                VALUES (?, ?, 'MATCHED', 'SUCCESS', 'test', 'NONE')
                """, rows.stream().map(r -> new Object[] {runId, r[0]}).toList());

        SettlementReport r = settlement.closeAndSettle(null);

        assertEquals("SETTLED", r.status());
        assertEquals(1_000, r.items().size());
        assertEquals(0, r.netPositionsSum());
        assertEquals(expected, r.netPositions().stream()
                .collect(Collectors.toMap(SettlementReport.Position::bankCode, SettlementReport.Position::netAmount)));
        assertEquals(gross, r.grossVsNet().grossAmount());
        assertTrue(r.grossVsNet().netAmountMoved() < gross, "netting must move less than gross");
    }

    // ---- what joins a cycle ------------------------------------------------------------------

    /**
     * DEEMED_APPROVED and recon exceptions stay out. Once a later recon run resolves them, they
     * join the NEXT cycle.
     */
    @Test
    void deemedApprovedAndReconExceptionsRollIntoTheNextCycleOnceResolved() {
        Instant from = dbNow();
        String clean = paid("ravi@banka", "anu@bankb", 20_000);
        // Deemed: Ravi debited, Anu's bank never answered (and does not answer the status check).
        String deemed = txnIn(TxnState.DEEMED_APPROVED, "ravi@banka", "anu@bankb", 20_000);
        books("BANKA", deemed, OpType.DEBIT, 20_000);
        // Exception: the switch says SUCCESS, but Bank B has no record of the credit -> SWITCH_ONLY.
        String lost = txnIn(TxnState.SUCCESS, "ravi@banka", "anu@bankb", 20_000);
        books("BANKA", lost, OpType.DEBIT, 20_000);
        Instant to = dbNow();
        recon.run(from, to.plusMillis(1));
        assertEquals(TxnState.DEEMED_APPROVED, txns.get(deemed).state());

        SettlementReport first = settlement.closeAndSettle(null);

        assertEquals(Set.of(clean), txnIds(first));

        // Later: Bank B's books show both credits after all (it was down, then its record was fixed).
        books("BANKB", deemed, OpType.CREDIT, 20_000);
        books("BANKB", lost, OpType.CREDIT, 20_000);
        recon.run(from.minusMillis(1), to.plusMillis(1));                // a new recon run
        assertEquals(TxnState.SUCCESS, txns.get(deemed).state());        // resolved by recon

        SettlementReport second = settlement.closeAndSettle(null);

        assertEquals(Set.of(deemed, lost), txnIds(second));
        assertEquals("SETTLED", second.status());
        assertEquals(List.of(-40_000L, 40_000L, 0L), nets(second));
    }

    @Test
    void reversedAndFailedPaymentsNeverJoin() {
        Instant from = dbNow();
        String reversed = txnIn(TxnState.REVERSED, "ravi@banka", "anu@bankb", 20_000);
        books("BANKA", reversed, OpType.DEBIT, 20_000);
        books("BANKA", reversed, OpType.REVERSE, 20_000);
        String failed = txnIn(TxnState.FAILED, "ravi@banka", "anu@bankb", 20_000);
        recon.run(from, dbNow().plusMillis(1));

        SettlementReport r = settlement.closeAndSettle(null);

        assertTrue(r.items().isEmpty(), r.items().toString());
        assertEquals("SETTLED", r.status());
        assertFalse(txnIds(r).contains(failed));
    }

    // ---- idempotency ---------------------------------------------------------------------------

    @Test
    void closingOrSettlingTheSameCycleTwiceChangesNothing() {
        Instant from = dbNow();
        paid("ravi@banka", "kumar@bankc", 30_000);
        reconcile(from);
        SettlementReport first = settlement.closeAndSettle(null);
        Map<String, Long> reserves = central.reserves();

        SettlementReport closedAgain = settlement.closeAndSettle(first.cycleId());
        SettlementReport settledAgain = settlement.settle(first.cycleId());

        assertEquals(first, closedAgain);
        assertEquals(first, settledAgain);
        assertEquals(reserves, central.reserves());
        assertEquals(1, centralEntries(first.cycleId()));
        verify(adviceClient, times(3)).send(any(), argThat(a -> a.cycleId() == first.cycleId()));
    }

    @Test
    void twoClosesAtTheSameTimeNeverPutAPaymentInTwoCycles() throws Exception {
        Instant from = dbNow();
        String id = paid("ravi@banka", "anu@bankb", 20_000);
        reconcile(from);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Future<SettlementReport>> results = new CopyOnWriteArrayList<>();
        for (int i = 0; i < 2; i++) {
            results.add(pool.submit(() -> settlement.closeAndSettle(null)));
        }
        List<SettlementReport> reports = new ArrayList<>();
        for (Future<SettlementReport> f : results) {
            reports.add(f.get());
        }
        pool.shutdown();

        assertEquals(1, reports.stream().filter(r -> txnIds(r).contains(id)).count());
        assertEquals(1, jdbc.sql("SELECT COUNT(*) FROM settlement_items WHERE txn_id = :t AND active")
                .param("t", id).query(Integer.class).single());
    }

    @Test
    void aBankThatDoesNotConfirmIsAdvisedAgainLaterAndOnlyThenTheCycleIsSettled() {
        Instant from = dbNow();
        paid("ravi@banka", "kumar@bankc", 50_000);
        reconcile(from);
        when(adviceClient.send(argThat(b -> b != null && b.code().equals("BANKC")), any())).thenReturn(false);

        SettlementReport first = settlement.closeAndSettle(null);

        assertEquals("CLOSED", first.status());
        assertEquals(List.of(true, true, false), first.netPositions().stream().map(SettlementReport.Position::advised).toList());
        assertEquals(1, centralEntries(first.cycleId()));                 // the central bank already moved it

        when(adviceClient.send(argThat(b -> b != null && b.code().equals("BANKC")), any())).thenReturn(true);
        SettlementReport retried = settlement.settle(first.cycleId());

        assertEquals("SETTLED", retried.status());
        assertEquals(1, centralEntries(first.cycleId()));                 // still ONE entry
        verify(adviceClient, times(1)).send(bank("BANKA"), argThat(a -> a.cycleId() == first.cycleId()));
        verify(adviceClient, times(2)).send(bank("BANKC"), argThat(a -> a.cycleId() == first.cycleId()));
    }

    // ---- the safety check ----------------------------------------------------------------------

    @Test
    void aPlantedBugThatBreaksTheSumFailsTheCycleAndSettlesNothing(CapturedOutput output) {
        Instant from = dbNow();
        String id = paid("ravi@banka", "anu@bankb", 20_000);
        reconcile(from);
        // The bug: Bank A's position comes out 1 paisa too high.
        doAnswer(inv -> {
            @SuppressWarnings("unchecked")
            Map<String, Long> nets = (Map<String, Long>) inv.callRealMethod();
            nets.merge("BANKA", 1L, Long::sum);
            return nets;
        }).when(calculator).compute(any(), any());
        Map<String, Long> reserves = central.reserves();

        SettlementReport failed = settlement.closeAndSettle(null);

        assertEquals("FAILED", failed.status());
        assertEquals("net positions sum to 1 paise, expected 0", failed.failureReason());
        assertEquals(1, failed.netPositionsSum());
        assertEquals(reserves, central.reserves());                      // the central bank moved nothing
        assertEquals(0, centralEntries(failed.cycleId()));
        verify(adviceClient, never()).send(any(), any());                // no bank was told anything
        assertTrue(output.getOut().contains("SETTLEMENT CYCLE " + failed.cycleId() + " FAILED"));
        assertThrows(IllegalStateException.class, () -> settlement.settle(failed.cycleId()));

        // Bug fixed: the payment was released and settles in the next cycle.
        reset(calculator);
        SettlementReport next = settlement.closeAndSettle(null);

        assertEquals("SETTLED", next.status());
        assertEquals(Set.of(id), txnIds(next));
    }

    @Test
    void anOpenCycleCannotBeSettledAndAnUnknownCycleIsNotFound() {
        long open = jdbc.sql("SELECT id FROM settlement_cycles WHERE status = 'OPEN'").query(Long.class).single();

        assertThrows(IllegalStateException.class, () -> settlement.settle(open));
        assertThrows(SettlementCycleNotFoundException.class, () -> settlement.report(999_999));
        assertNull(settlement.report(open).closedAt());
    }

    // ---- helpers -------------------------------------------------------------------------------

    /** A SUCCESS payment, and both banks' books agreeing with it. */
    private String paid(String payer, String payee, long amount) {
        String id = txnIn(TxnState.SUCCESS, payer, payee, amount);
        books(bankOf(payer), id, OpType.DEBIT, amount);
        books(bankOf(payee), id, OpType.CREDIT, amount);
        return id;
    }

    private String txnIn(TxnState target, String payer, String payee, long amount) {
        String id = "TXN-" + UUID.randomUUID();
        txns.create(id, payer, payee, amount);
        List<TxnState> path = switch (target) {
            case SUCCESS -> List.of(TxnState.DEBIT_SENT, TxnState.DEBITED, TxnState.CREDIT_SENT, TxnState.SUCCESS);
            case DEEMED_APPROVED -> List.of(TxnState.DEBIT_SENT, TxnState.DEBITED, TxnState.CREDIT_SENT,
                    TxnState.DEEMED_APPROVED);
            case REVERSED -> List.of(TxnState.DEBIT_SENT, TxnState.DEBITED, TxnState.REVERSAL_SENT, TxnState.REVERSED);
            case FAILED -> List.of(TxnState.DEBIT_SENT, TxnState.FAILED);
            default -> throw new IllegalArgumentException(target.name());
        };
        for (TxnState s : path) {
            txns.transition(id, s, "test setup");
        }
        return id;
    }

    private void books(String bankCode, String txnId, OpType op, long amount) {
        bankBooks.computeIfAbsent(bankCode, k -> new CopyOnWriteArrayList<>())
                .add(new ReconEntry(txnId, op, ReconStatus.SUCCESS, amount, dbNow()));
    }

    /** Reconciles everything created since {@code from}. */
    private void reconcile(Instant from) {
        recon.run(from, dbNow().plusMillis(1));
    }

    private static String bankOf(String vpa) {
        return vpa.substring(vpa.indexOf('@') + 1).toUpperCase();
    }

    private static BankRoute bank(String code) {
        return argThat(b -> b != null && b.code().equals(code));
    }

    private static Set<String> txnIds(SettlementReport r) {
        return r.items().stream().map(SettlementItem::txnId).collect(Collectors.toSet());
    }

    private static List<Long> nets(SettlementReport r) {
        return r.netPositions().stream().map(SettlementReport.Position::netAmount).toList();
    }

    private int centralEntries(long cycleId) {
        return jdbc.sql("SELECT COUNT(*) FROM central_journal_entries WHERE cycle_id = :c")
                .param("c", cycleId).query(Integer.class).single();
    }

    private Instant dbNow() {
        return jdbc.sql("SELECT clock_timestamp()").query(OffsetDateTime.class).single().toInstant();
    }
}
