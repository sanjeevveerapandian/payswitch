package com.payswitch.load.audit;

import com.fasterxml.jackson.databind.JsonNode;
import com.payswitch.load.Args;
import com.payswitch.load.Http;
import com.payswitch.load.audit.Books.BankBooks;
import com.payswitch.load.audit.Books.CentralBooks;
import com.payswitch.load.audit.Books.Customer;
import com.payswitch.load.audit.Books.SwitchTxn;
import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Consumer;

/**
 * Proves (or disproves) that no money was lost. Runnable any time, against the running system:
 *
 * <pre>
 *   java -cp load-generator/target/load-generator-0.1.0-SNAPSHOT-all.jar com.payswitch.load.audit.MoneyAuditor
 * </pre>
 *
 * <p>Steps:
 * <ol>
 *   <li>Prepare (skip with --prepare off): wait until the resolver has finished every stuck payment,
 *       reconcile the whole window, wait again (recon may have started reversals of DEEMED_APPROVED
 *       payments), then close and settle the settlement cycle.</li>
 *   <li>Read all four databases DIRECTLY with SQL. Not through the services' APIs: the auditor must
 *       not trust the code it is auditing.</li>
 *   <li>Run the checks ({@link MoneyChecks}), print PASS/FAIL, and for every FAIL the first 20
 *       offenders with their full switch history and bank entries.</li>
 * </ol>
 */
public final class MoneyAuditor {

    private static final String UNFINISHED_SQL =
            "state IN ('INITIATED', 'DEBIT_SENT', 'DEBITED', 'CREDIT_SENT', 'REVERSAL_SENT')";
    private static final int EXPLAIN_LIMIT = 20;

    private final AuditConfig config;
    private final Http http = new Http(Duration.ofSeconds(5));
    private final Consumer<String> log;

    public MoneyAuditor(AuditConfig config, Consumer<String> log) {
        this.config = config;
        this.log = log;
    }

    public static void main(String[] argv) throws Exception {
        Args args = new Args(argv, Set.of("switch-url", "db-url", "db-user", "db-password", "prepare",
                "expected-total", "timeout-seconds", "recon-cutoff-seconds"));
        AuditConfig d = AuditConfig.dockerDefaults();
        AuditConfig config = new AuditConfig(args.text("switch-url", d.switchUrl()), args.text("db-url", d.jdbcBase()),
                args.text("db-user", d.user()), args.text("db-password", d.password()), d.bankDatabases(),
                d.switchDatabase(), args.onOff("prepare", true),
                Duration.ofSeconds(args.longValue("timeout-seconds", d.waitTimeout().toSeconds())),
                args.has("expected-total") ? OptionalLong.of(args.longValue("expected-total", 0)) : OptionalLong.empty(),
                Duration.ofSeconds(args.longValue("recon-cutoff-seconds", d.reconCutoff().toSeconds())));
        MoneyAuditor auditor = new MoneyAuditor(config, line -> System.out.println("[audit] " + line));
        AuditReport report = auditor.audit();
        System.out.println();
        System.out.println(format(report));
        System.exit(report.passed() ? 0 : 1);
    }

    // ---- the whole audit ----------------------------------------------------------------------

    public AuditReport audit() throws SQLException, IOException, InterruptedException {
        long t0 = System.nanoTime();
        String settlement = "not prepared (--prepare off): nothing closed or settled";
        if (config.prepare()) {
            settlement = prepare();
        }
        long t1 = System.nanoTime();
        Books books = readBooks();
        List<CheckResult> checks = MoneyChecks.run(books, config.expectedTotal());
        List<String> explanations = new ArrayList<>();
        for (CheckResult c : checks) {
            if (!c.pass()) {
                explanations.add(explain(c, books));
            }
        }
        Map<String, String> finalStates = new HashMap<>();
        Map<String, Integer> stateCounts = new TreeMap<>();
        for (SwitchTxn t : books.txns()) {
            finalStates.put(t.txnId(), t.state());
            stateCounts.merge(t.state(), 1, Integer::sum);
        }
        Map<String, Integer> recon = new TreeMap<>();
        books.latestReconCategory().values().forEach(c -> recon.merge(c, 1, Integer::sum));
        long t2 = System.nanoTime();
        return new AuditReport(checks, finalStates, stateCounts, recon, fixes(), settlement, explanations,
                Duration.ofNanos(t1 - t0), Duration.ofNanos(t2 - t1));
    }

    /** Total money in every customer account right now, in paise. Take it BEFORE a run. */
    public long totalCustomerMoney() throws SQLException {
        long total = 0;
        for (String database : config.bankDatabases().values()) {
            try (Connection c = connect(database)) {
                total += single(c, "SELECT COALESCE(SUM(-raw_balance), 0) FROM accounts WHERE vpa IS NOT NULL");
            }
        }
        return total;
    }

    // ---- 1. prepare ---------------------------------------------------------------------------

    private String prepare() throws SQLException, IOException, InterruptedException {
        Instant deadline = Instant.now().plus(config.waitTimeout());
        try (Connection sw = connect(config.switchDatabase())) {
            waitUntilZero(sw, "stuck payments (resolver still working)", "SELECT COUNT(*) FROM transactions WHERE "
                    + UNFINISHED_SQL, deadline);

            Instant[] window = window(sw);
            if (window == null) {
                return "no payments: nothing to reconcile or settle";
            }
            waitForReconCutoff(sw, window[1]);
            reconcile(window[0], window[1], deadline);

            // Recon finishes DEEMED_APPROVED payments; one with no credit is reversed, which can
            // leave it in REVERSAL_SENT for a moment. Wait for everything to be final.
            waitUntilZero(sw, "unfinished payments after recon", "SELECT COUNT(*) FROM transactions WHERE "
                    + UNFINISHED_SQL + " OR state = 'DEEMED_APPROVED'", deadline);
        }
        return settle(deadline);
    }

    private static Instant[] window(Connection sw) throws SQLException {
        try (ResultSet rs = sw.createStatement().executeQuery("SELECT MIN(created_at), MAX(created_at) FROM transactions")) {
            rs.next();
            OffsetDateTime min = rs.getObject(1, OffsetDateTime.class);
            OffsetDateTime max = rs.getObject(2, OffsetDateTime.class);
            if (min == null) {
                return null;
            }
            return new Instant[] {min.toInstant(), max.toInstant().plusMillis(1)};
        }
    }

    /**
     * Recon leaves payments younger than its cutoff alone. Calling it before the newest payment is
     * that old only produces a PARTIAL run that must read every bank again; so wait first.
     */
    private void waitForReconCutoff(Connection sw, Instant windowEnd) throws SQLException, InterruptedException {
        Instant ready = windowEnd.plus(config.reconCutoff()).plusSeconds(1);
        try (ResultSet rs = sw.createStatement().executeQuery("SELECT clock_timestamp()")) {
            rs.next();
            Instant dbNow = rs.getObject(1, OffsetDateTime.class).toInstant();
            long waitMs = Duration.between(dbNow, ready).toMillis();
            if (waitMs > 0) {
                log.accept("waiting " + waitMs + " ms until the newest payment is older than the recon cutoff");
                Thread.sleep(waitMs);
            }
        }
    }

    private void waitUntilZero(Connection sw, String what, String countSql, Instant deadline)
            throws SQLException, InterruptedException {
        long lastLog = 0;
        while (true) {
            long n = single(sw, countSql);
            if (n == 0) {
                log.accept(what + ": 0");
                return;
            }
            if (Instant.now().isAfter(deadline)) {
                log.accept(what + ": still " + n + " at the timeout. Checking anyway (check 2 will FAIL).");
                return;
            }
            if (System.currentTimeMillis() - lastLog > 10_000) {
                log.accept(what + ": " + n + ", waiting...");
                lastLog = System.currentTimeMillis();
            }
            Thread.sleep(1_000);
        }
    }

    /**
     * One recon run over the whole window. Payments younger than the switch's recon cutoff are
     * skipped (PARTIAL), so call again until COMPLETED. Calling again is safe: a window has exactly
     * one run, and a rerun only looks at txnIds it has no line for yet.
     */
    private void reconcile(Instant from, Instant to, Instant deadline) throws IOException, InterruptedException {
        String url = config.switchUrl() + "/admin/recon/run?from=" + from + "&to=" + to;
        log.accept("recon " + from + " .. " + to);
        while (true) {
            Duration left = Duration.between(Instant.now(), deadline);
            Http.Response r;
            try {
                r = http.post(url, null, left.compareTo(Duration.ofMinutes(1)) > 0 ? left : Duration.ofMinutes(1));
            } catch (IOException e) {
                // A slow or interrupted call (e.g. a timeout) is not a verdict: the run keeps going on
                // the switch, or is taken over after its lease. Ask again until the deadline.
                if (Instant.now().isAfter(deadline)) {
                    log.accept("recon call failed at the timeout: " + e);
                    return;
                }
                log.accept("recon call failed (" + e + "), asking again...");
                Thread.sleep(3_000);
                continue;
            }
            JsonNode body = r.json();
            String status = body.path("status").asText("HTTP " + r.status());
            if ("COMPLETED".equals(status)) {
                log.accept("recon COMPLETED: " + body.path("counts"));
                return;
            }
            if (Instant.now().isAfter(deadline)) {
                log.accept("recon still " + status + " at the timeout: " + r.body());
                return;
            }
            log.accept("recon " + status + " " + body.path("counts") + ", running it again shortly...");
            Thread.sleep(3_000);
        }
    }

    /** Close the open cycle and settle it; retry settling while some bank has not confirmed. */
    private String settle(Instant deadline) throws IOException, InterruptedException {
        JsonNode cycle = http.postOk(config.switchUrl() + "/admin/settlement/close", null, Duration.ofMinutes(30));
        long id = cycle.path("cycleId").asLong();
        while ("CLOSED".equals(cycle.path("status").asText()) && Instant.now().isBefore(deadline)) {
            Thread.sleep(2_000);
            cycle = http.postOk(config.switchUrl() + "/admin/settlement/cycles/" + id + "/settle", null,
                    Duration.ofMinutes(5));
        }
        JsonNode gvn = cycle.path("grossVsNet");
        String line = String.format("cycle %d %s: %d payments, gross %s paise, net moved %s paise (%s%% saved), "
                        + "net positions %s", id, cycle.path("status").asText(), cycle.path("items").size(),
                gvn.path("grossAmount").asText(), gvn.path("netAmountMoved").asText(),
                gvn.path("percentSaved").asText(), positions(cycle));
        log.accept("settlement " + line);
        return line;
    }

    private static String positions(JsonNode cycle) {
        Map<String, Long> p = new TreeMap<>();
        cycle.path("netPositions").forEach(n -> p.put(n.path("bankCode").asText(), n.path("netAmount").asLong()));
        return p.toString();
    }

    // ---- 2. read the books --------------------------------------------------------------------

    private Books readBooks() throws SQLException {
        List<SwitchTxn> txns = new ArrayList<>();
        Map<String, String> latestRecon = new HashMap<>();
        CentralBooks central;
        try (Connection sw = connect(config.switchDatabase())) {
            query(sw, "SELECT txn_id, LOWER(payer_vpa), LOWER(payee_vpa), amount, state FROM transactions", rs ->
                    txns.add(new SwitchTxn(rs.getString(1), rs.getString(2), rs.getString(3), rs.getLong(4),
                            rs.getString(5))));
            query(sw, "SELECT DISTINCT ON (txn_id) txn_id, category FROM recon_items ORDER BY txn_id, id DESC", rs ->
                    latestRecon.put(rs.getString(1), rs.getString(2)));
            central = readCentral(sw);
        }
        List<BankBooks> banks = new ArrayList<>();
        for (Map.Entry<String, String> bank : new TreeMap<>(config.bankDatabases()).entrySet()) {
            try (Connection c = connect(bank.getValue())) {
                banks.add(readBank(bank.getKey(), c));
            }
        }
        return new Books(txns, banks, central, latestRecon);
    }

    private static CentralBooks readCentral(Connection sw) throws SQLException {
        Map<String, Long> raw = new TreeMap<>();
        Map<String, Long> lines = new TreeMap<>();
        Map<Long, Long> netSums = new TreeMap<>();
        query(sw, "SELECT id, raw_balance FROM central_accounts", rs -> raw.put(rs.getString(1), rs.getLong(2)));
        query(sw, "SELECT account_id, SUM(amount) FROM central_entry_lines GROUP BY account_id",
                rs -> lines.put(rs.getString(1), rs.getLong(2)));
        query(sw, """
                SELECT c.id, COALESCE(SUM(n.net_amount), 0) FROM settlement_cycles c
                LEFT JOIN net_positions n ON n.cycle_id = c.id
                WHERE c.status IN ('CLOSED', 'SETTLED') GROUP BY c.id
                """, rs -> netSums.put(rs.getLong(1), rs.getLong(2)));
        int unbalanced = (int) single(sw, "SELECT COUNT(*) FROM (SELECT journal_entry_id FROM central_entry_lines "
                + "GROUP BY journal_entry_id HAVING SUM(amount) <> 0) x");
        int failed = (int) single(sw, "SELECT COUNT(*) FROM settlement_cycles WHERE status = 'FAILED'");
        return new CentralBooks(raw, lines, unbalanced, netSums, failed);
    }

    private static BankBooks readBank(String code, Connection c) throws SQLException {
        List<Customer> customers = new ArrayList<>();
        // Customer accounts are liabilities: the customer's money = -raw_balance.
        query(c, """
                SELECT LOWER(a.vpa), -a.raw_balance,
                       COALESCE((SELECT -SUM(l.amount) FROM entry_lines l JOIN journal_entries e ON e.id = l.journal_entry_id
                                 WHERE l.account_id = a.id AND e.op_type = 'OPENING'), 0)
                FROM accounts a WHERE a.vpa IS NOT NULL
                """, rs -> customers.add(new Customer(code, rs.getString(1), rs.getLong(2), rs.getLong(3))));

        Map<String, Map<String, Integer>> ops = new HashMap<>();
        query(c, "SELECT txn_id, op_type, COUNT(*) FROM journal_entries WHERE op_type IN ('DEBIT', 'CREDIT', 'REVERSE') "
                + "GROUP BY txn_id, op_type", rs -> ops.computeIfAbsent(rs.getString(1), k -> new HashMap<>())
                .put(rs.getString(2), rs.getInt(3)));

        // Every movement of customer money that is not an opening deposit, whatever its op type.
        Map<String, Map<String, Long>> effects = new HashMap<>();
        query(c, """
                SELECT e.txn_id, LOWER(a.vpa), SUM(-l.amount) FROM entry_lines l
                JOIN journal_entries e ON e.id = l.journal_entry_id
                JOIN accounts a ON a.id = l.account_id
                WHERE a.vpa IS NOT NULL AND e.op_type <> 'OPENING'
                GROUP BY e.txn_id, a.vpa
                """, rs -> effects.computeIfAbsent(rs.getString(1), k -> new HashMap<>())
                .put(rs.getString(2), rs.getLong(3)));

        long settlement = single(c, "SELECT -raw_balance FROM accounts WHERE id = 'SWITCH_SETTLEMENT'");
        long reserve = single(c, "SELECT COALESCE((SELECT raw_balance FROM accounts WHERE id = 'CENTRAL_BANK_RESERVE'), 0)");
        long rawSum = single(c, "SELECT COALESCE(SUM(raw_balance), 0) FROM accounts");
        int unbalanced = (int) single(c, "SELECT COUNT(*) FROM (SELECT journal_entry_id FROM entry_lines "
                + "GROUP BY journal_entry_id HAVING SUM(amount) <> 0) x");
        int offJournal = (int) single(c, """
                SELECT COUNT(*) FROM accounts a
                LEFT JOIN (SELECT account_id, SUM(amount) AS s FROM entry_lines GROUP BY account_id) l ON l.account_id = a.id
                WHERE a.raw_balance <> COALESCE(l.s, 0)
                """);
        return new BankBooks(code, customers, ops, effects, settlement, reserve, rawSum, unbalanced, offJournal);
    }

    // ---- 3. explain failures ------------------------------------------------------------------

    private String explain(CheckResult check, Books books) throws SQLException {
        StringBuilder out = new StringBuilder();
        out.append("---- ").append(check.line()).append('\n');
        out.append("First ").append(Math.min(EXPLAIN_LIMIT, check.offenders().size())).append(" of ")
                .append(check.offenders().size()).append(" offenders:\n");
        List<String> first = check.offenders().subList(0, Math.min(EXPLAIN_LIMIT, check.offenders().size()));
        Set<String> txnIds = MoneyChecks.txnIdsIn(first, books);
        for (String offender : first) {
            if (!txnIds.contains(offender)) {
                out.append("  * ").append(offender).append('\n');      // a description or a customer
            }
        }
        for (String txnId : txnIds) {
            out.append(history(txnId));
        }
        return out.toString();
    }

    /** Everything every database knows about one txnId. */
    public String history(String txnId) throws SQLException {
        StringBuilder out = new StringBuilder("  * ").append(txnId).append('\n');
        try (Connection sw = connect(config.switchDatabase())) {
            queryWith(sw, "SELECT payer_vpa, payee_vpa, amount, state, created_at FROM transactions WHERE txn_id = ?",
                    txnId, rs -> out.append("      switch: ").append(rs.getString(1)).append(" -> ").append(rs.getString(2))
                            .append(", ").append(rs.getLong(3)).append(" paise, ").append(rs.getString(4))
                            .append(", created ").append(rs.getObject(5, OffsetDateTime.class)).append('\n'));
            queryWith(sw, "SELECT created_at, from_state, to_state, reason FROM txn_events WHERE txn_id = ? ORDER BY id",
                    txnId, rs -> out.append("        event ").append(rs.getObject(1, OffsetDateTime.class)).append("  ")
                            .append(rs.getString(2)).append(" -> ").append(rs.getString(3)).append("  ")
                            .append(rs.getString(4)).append('\n'));
            queryWith(sw, "SELECT run_id, category, switch_state, bank_view, action_taken FROM recon_items "
                    + "WHERE txn_id = ? ORDER BY id", txnId, rs -> out.append("        recon run ").append(rs.getLong(1))
                    .append(": ").append(rs.getString(2)).append(" (").append(rs.getString(3)).append(") ")
                    .append(rs.getString(4)).append(" -> ").append(rs.getString(5)).append('\n'));
        }
        for (Map.Entry<String, String> bank : new TreeMap<>(config.bankDatabases()).entrySet()) {
            try (Connection c = connect(bank.getValue())) {
                queryWith(c, "SELECT op_type, status, created_at FROM processed_requests WHERE txn_id = ? ORDER BY id",
                        txnId, rs -> out.append("      ").append(bank.getKey()).append(" request ").append(rs.getString(1))
                                .append(" ").append(rs.getString(2)).append(" at ")
                                .append(rs.getObject(3, OffsetDateTime.class)).append('\n'));
                queryWith(c, """
                        SELECT e.op_type, l.account_id, l.amount FROM journal_entries e
                        JOIN entry_lines l ON l.journal_entry_id = e.id WHERE e.txn_id = ? ORDER BY l.id
                        """, txnId, rs -> out.append("      ").append(bank.getKey()).append(" entry ")
                        .append(rs.getString(1)).append(" ").append(rs.getString(2)).append(" ")
                        .append(rs.getLong(3)).append('\n'));
            }
        }
        return out.toString();
    }

    // ---- stats ------------------------------------------------------------------------------

    /** How many payments needed help, and from what. */
    private Map<String, Long> fixes() throws SQLException {
        Map<String, Long> fixes = new LinkedHashMap<>();
        try (Connection sw = connect(config.switchDatabase())) {
            fixes.put("bank answer UNKNOWN at least once (timeout / HTTP 500), resolved by status checks",
                    single(sw, "SELECT COUNT(DISTINCT txn_id) FROM txn_events WHERE reason LIKE '%UNKNOWN%'"));
            fixes.put("outcome confirmed by a status check (debit or credit found at the bank)",
                    single(sw, "SELECT COUNT(DISTINCT txn_id) FROM txn_events WHERE reason LIKE '%BY_STATUS'"));
            fixes.put("found in INITIATED after a crash, closed as FAILED (never sent to a bank)",
                    single(sw, "SELECT COUNT(DISTINCT txn_id) FROM txn_events WHERE reason = 'NEVER_SENT'"));
            fixes.put("reached DEEMED_APPROVED",
                    single(sw, "SELECT COUNT(DISTINCT txn_id) FROM txn_events WHERE to_state = 'DEEMED_APPROVED'"));
            fixes.put("finished by recon (RESOLVED_DEEMED)",
                    single(sw, "SELECT COUNT(*) FROM (SELECT DISTINCT ON (txn_id) category FROM recon_items "
                            + "ORDER BY txn_id, id DESC) x WHERE category = 'RESOLVED_DEEMED'"));
            fixes.put("reversed (payer refunded)", single(sw, "SELECT COUNT(*) FROM transactions WHERE state = 'REVERSED'"));
            fixes.put("declined for insufficient funds",
                    single(sw, "SELECT COUNT(DISTINCT txn_id) FROM txn_events WHERE reason LIKE '%INSUFFICIENT_FUNDS%'"));
        }
        return fixes;
    }

    // ---- report -------------------------------------------------------------------------------

    public static String format(AuditReport r) {
        StringBuilder out = new StringBuilder();
        out.append("MONEY AUDIT: ").append(r.passed() ? "PASS" : "FAIL").append('\n');
        r.checks().forEach(c -> out.append("  ").append(c.line()).append('\n'));
        out.append("Final states: ").append(r.stateCounts()).append('\n');
        out.append("Recon (latest line per txnId): ").append(r.reconCategories()).append('\n');
        out.append("Settlement: ").append(r.settlement()).append('\n');
        out.append("Help needed:\n");
        r.fixes().forEach((what, n) -> out.append("  ").append(n).append("  ").append(what).append('\n'));
        out.append("Audit time: prepare ").append(r.prepareTime().toSeconds()).append(" s, checks ")
                .append(r.checkTime().toSeconds()).append(" s\n");
        r.explanations().forEach(e -> out.append('\n').append(e));
        return out.toString();
    }

    // ---- JDBC helpers -----------------------------------------------------------------------

    private interface RowHandler {
        void row(ResultSet rs) throws SQLException;
    }

    private Connection connect(String database) throws SQLException {
        return DriverManager.getConnection(config.jdbcBase() + database, config.user(), config.password());
    }

    private static long single(Connection c, String sql) throws SQLException {
        try (ResultSet rs = c.createStatement().executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static void query(Connection c, String sql, RowHandler handler) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setFetchSize(5_000);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    handler.row(rs);
                }
            }
        }
    }

    private static void queryWith(Connection c, String sql, String param, RowHandler handler) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, param);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    handler.row(rs);
                }
            }
        }
    }
}
