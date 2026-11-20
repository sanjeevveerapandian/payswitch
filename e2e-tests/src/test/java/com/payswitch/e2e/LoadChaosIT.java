package com.payswitch.e2e;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.payswitch.load.LoadGenerator;
import com.payswitch.load.LoadGenerator.LoadResult;
import com.payswitch.load.RunReport;
import com.payswitch.load.SwitchCrasher;
import com.payswitch.load.audit.AuditConfig;
import com.payswitch.load.audit.AuditReport;
import com.payswitch.load.audit.CheckResult;
import com.payswitch.load.audit.MoneyAuditor;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Phase 10, small version: 5,000 payments with chaos on every bank, then the full money audit.
 * Runs in "mvn verify" only (failsafe, *IT), because it takes about a minute.
 *
 * <p>Its own fresh system (not the shared one): the audit checks EVERY payment in the databases,
 * and the other e2e tests deliberately leave broken payments behind.
 */
class LoadChaosIT {

    private static final Consumer<String> LOG = line -> System.out.println("[load-it] " + line);

    @Test
    void fiveThousandPaymentsUnderChaosLoseNoMoney() throws Exception {
        try (PaymentSystem system = PaymentSystem.startForLoadTest()) {
            AuditConfig audit = new AuditConfig(system.switchUrl(), system.jdbcBase(), system.dbUser(),
                    system.dbPassword(), Map.of("BANKA", "banka", "BANKB", "bankb", "BANKC", "bankc"), "switch",
                    true, Duration.ofMinutes(3), OptionalLong.empty(), PaymentSystem.RECON_CUTOFF);
            long startTotal = new MoneyAuditor(audit, LOG).totalCustomerMoney();

            SwitchCrasher noCrash = log -> {
                throw new IllegalStateException("no crash in this test");
            };
            LoadGenerator.Config config = new LoadGenerator.Config(5_000, 50, 2024, true, null, system.pspUrl(),
                    List.of(system.bankAUrl(), system.bankBUrl(), system.bankCUrl()), noCrash,
                    Duration.ofSeconds(30), Duration.ofMillis(200));
            LoadResult load = new LoadGenerator(config, LOG).runWithChaos();
            AuditReport report = new MoneyAuditor(audit.withExpectedTotal(startTotal), LOG).audit();
            String text = RunReport.format("LoadChaosIT", config, load, report);
            System.out.println(text);

            assertTrue(RunReport.passed(load, report), text);
            assertEquals(5_000, load.results().size());
            // The chaos really happened (a test that never meets a failure proves little).
            assertTrue(report.fixes().values().iterator().next() > 0, "no bank answer was ever UNKNOWN");
            assertTrue(report.stateCounts().getOrDefault("REVERSED", 0) > 0, "nothing was ever reversed");

            // And the auditor really reads the databases: plant one rogue paisa at Bank A and look again.
            plantRoguePaisa(system.bankADb());
            AuditReport planted = new MoneyAuditor(new AuditConfig(audit.switchUrl(), audit.jdbcBase(), audit.user(),
                    audit.password(), audit.bankDatabases(), audit.switchDatabase(), false, audit.waitTimeout(),
                    OptionalLong.of(startTotal), audit.reconCutoff()), LOG).audit();
            assertFalse(planted.passed());
            List<Integer> failed = planted.checks().stream().filter(c -> !c.pass()).map(CheckResult::number).toList();
            assertEquals(List.of(1, 4, 5), failed, MoneyAuditor.format(planted));
        }
    }

    /** A credit to cust001@banka that no payment explains: Bank A's books still balance. */
    private static void plantRoguePaisa(JdbcClient bankA) {
        bankA.sql("INSERT INTO journal_entries (id, txn_id, op_type, description) "
                + "VALUES ('ROGUE-1', 'ROGUE-1', 'CREDIT', 'planted by the test')").update();
        bankA.sql("INSERT INTO entry_lines (journal_entry_id, account_id, amount) VALUES "
                + "('ROGUE-1', 'BANK_CASH', 1), ('ROGUE-1', 'ACC-LOAD-001', -1)").update();
        bankA.sql("UPDATE accounts SET raw_balance = raw_balance + 1 WHERE id = 'BANK_CASH'").update();
        bankA.sql("UPDATE accounts SET raw_balance = raw_balance - 1 WHERE id = 'ACC-LOAD-001'").update();
    }
}
