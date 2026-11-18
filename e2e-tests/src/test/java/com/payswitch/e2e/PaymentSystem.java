package com.payswitch.e2e;

import com.payswitch.bank.BankServiceApplication;
import com.payswitch.bank.service.LedgerAuditService;
import com.payswitch.psp.PspServiceApplication;
import com.payswitch.switchservice.SwitchServiceApplication;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * The whole system, running inside this test JVM:
 *
 * <pre>
 *   PSP      ->  Switch      ->  Bank A
 *                            ->  Bank B        one Postgres container, several databases
 *                            ->  Bank C
 *   raw PSP  ->  raw Switch  ->  (same banks)
 * </pre>
 *
 * The main switch resolves unknowns (Phase 6). The "raw" switch has resolution switched off, so it
 * only makes the first attempt (Phase 5 behaviour); the Phase 5 chaos tests use it.
 *
 * <p>Each service is a separate Spring application with its own HTTP port, and they talk over
 * real HTTP, exactly like in Docker Compose. Started ONCE and shared by every e2e test class
 * ({@link #shared()}); stopped when the test JVM exits.
 *
 * <p>Why every setting is passed explicitly: all services are on one classpath here, and each has
 * a file called application.yml. Only one of those files can win, so we do not rely on any of
 * them. Command-line arguments override every file.
 */
final class PaymentSystem implements AutoCloseable {

    /** Short timeouts keep the tests fast. The switch gives up on a bank after 1 s... */
    static final String SWITCH_READ_TIMEOUT = "1s";
    /** ...and a dropped response stays silent 2 s, so the switch always gives up first. */
    static final String DROP_RESPONSE_SLEEP = "2s";

    /** Reconciliation leaves payments younger than this alone. */
    static final java.time.Duration RECON_CUTOFF = java.time.Duration.ofSeconds(1);

    private static PaymentSystem instance;

    private final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));
    private final RestClient http = RestClient.create();

    private ConfigurableApplicationContext bankA;
    private ConfigurableApplicationContext bankB;
    private ConfigurableApplicationContext bankC;
    private ConfigurableApplicationContext switchApp;
    private ConfigurableApplicationContext psp;
    private ConfigurableApplicationContext rawSwitch;
    private ConfigurableApplicationContext rawPsp;
    private List<String> switchArgs;
    private int switchPort;

    static synchronized PaymentSystem shared() {
        if (instance == null) {
            PaymentSystem system = new PaymentSystem();
            try {
                system.start();
            } catch (SQLException e) {
                system.close();
                throw new IllegalStateException("Could not start the payment system", e);
            }
            Runtime.getRuntime().addShutdownHook(new Thread(system::close));
            instance = system;
        }
        return instance;
    }

    private PaymentSystem() {
    }

    private void start() throws SQLException {
        postgres.start();
        try (Connection c = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             Statement s = c.createStatement()) {
            for (String db : List.of("banka", "bankb", "bankc", "switch", "switch_raw")) {
                s.execute("CREATE DATABASE " + db);
            }
        }

        // Start order = dependency order: banks first, then the switches, then the PSPs.
        bankA = bank("BANKA", "banka");
        bankB = bank("BANKB", "bankb");
        bankC = bank("BANKC", "bankc");

        switchArgs = switchArgs("switch", true);
        switchApp = run(SwitchServiceApplication.class, switchArgs, 0);
        switchPort = port(switchApp);
        psp = run(PspServiceApplication.class, pspArgs(switchUrl()), 0);

        rawSwitch = run(SwitchServiceApplication.class, switchArgs("switch_raw", false), 0);
        rawPsp = run(PspServiceApplication.class, pspArgs(rawSwitchUrl()), 0);

        // Warm up: the very first request to a fresh app is slow (connection pools, class
        // loading). Do it now, so no test's first bank call trips the 1 s timeout.
        for (String url : List.of(bankAUrl() + "/accounts/ravi@banka/balance",
                bankBUrl() + "/accounts/anu@bankb/balance", bankCUrl() + "/accounts/kumar@bankc/balance",
                pspUrl() + "/actuator/health",
                switchUrl() + "/actuator/health", rawPspUrl() + "/actuator/health",
                rawSwitchUrl() + "/actuator/health")) {
            http.get().uri(url).retrieve().toBodilessEntity();
        }
    }

    /**
     * Stops the main switch and starts a fresh one on the same port and database, like a crash
     * and restart. Its startup recovery runs before this method returns.
     */
    void restartSwitch() {
        switchApp.close();
        switchApp = run(SwitchServiceApplication.class, switchArgs, switchPort);
    }

    private ConfigurableApplicationContext bank(String code, String database) {
        return run(BankServiceApplication.class, withDatabase(database,
                "--spring.application.name=bank-" + code,
                "--spring.profiles.active=chaos",               // /admin/chaos exists, all rates 0
                "--bank.chaos.drop-response-sleep=" + DROP_RESPONSE_SLEEP,
                "--bank.code=" + code,
                "--spring.flyway.locations=classpath:db/bank/migration,classpath:db/bank/seed/" + code), 0);
    }

    private List<String> switchArgs(String database, boolean resolution) {
        return withDatabase(database,
                "--spring.application.name=switch-service",
                "--spring.flyway.locations=classpath:db/switch/migration",
                "--switch.http.connect-timeout=2s",
                "--switch.http.read-timeout=" + SWITCH_READ_TIMEOUT,
                "--switch.banks.banka.code=BANKA",
                "--switch.banks.banka.url=" + bankAUrl(),
                "--switch.banks.bankb.code=BANKB",
                "--switch.banks.bankb.url=" + bankBUrl(),
                "--switch.banks.bankc.code=BANKC",
                "--switch.banks.bankc.url=" + bankCUrl(),
                // Fast retries: 50 ms, 100 ms between 3 attempts.
                "--switch.retry.base-delay=50ms",
                "--switch.retry.multiplier=2",
                "--switch.retry.jitter=0.2",
                "--switch.retry.max-attempts=3",
                "--switch.resolution.enabled=" + resolution,
                "--switch.resolution.inline-wait=3s",
                "--switch.resolution.stuck-after=0s",
                "--switch.resolution.interval=1h",              // tests never wait for the timer
                "--switch.resolution.batch-size=10",
                "--switch.resolution.lease=10s",
                "--switch.resolution.run-on-startup=true",
                // Reconciliation: tests run it directly (no timer). A 1 s cutoff keeps them fast;
                // pages of 3 make sure paging through /recon/entries is really exercised.
                "--switch.recon.enabled=false",
                "--switch.recon.cutoff=" + RECON_CUTOFF.toMillis() + "ms",
                "--switch.recon.bank-clock-slack=1m",
                "--switch.recon.page-size=3",
                // Settlement: tests close cycles themselves.
                "--switch.settlement.enabled=false");
    }

    private static List<String> pspArgs(String switchUrl) {
        return List.of(
                "--spring.application.name=psp-service",
                "--psp.switch-url=" + switchUrl,
                "--psp.http.connect-timeout=2s",
                "--psp.http.read-timeout=20s",
                // The PSP has no database. On this shared classpath Spring would still try to
                // create one because the JDBC jars are present, so switch that off.
                "--spring.autoconfigure.exclude="
                        + "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration",
                "--spring.flyway.enabled=false");
    }

    private List<String> withDatabase(String database, String... more) {
        List<String> args = new ArrayList<>(List.of(more));
        args.add("--spring.datasource.url=jdbc:postgresql://" + postgres.getHost() + ":"
                + postgres.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT) + "/" + database);
        args.add("--spring.datasource.username=" + postgres.getUsername());
        args.add("--spring.datasource.password=" + postgres.getPassword());
        return args;
    }

    /** @param port 0 = any free port */
    private static ConfigurableApplicationContext run(Class<?> app, List<String> args, int port) {
        List<String> all = new ArrayList<>(args);
        all.add("--server.port=" + port);
        all.add("--spring.main.banner-mode=off");
        all.add("--spring.jackson.deserialization.accept-float-as-int=false");   // as in every application.yml
        return new SpringApplicationBuilder(app).run(all.toArray(String[]::new));
    }

    private static int port(ConfigurableApplicationContext app) {
        return ((ServletWebServerApplicationContext) app).getWebServer().getPort();
    }

    private static String url(ConfigurableApplicationContext app) {
        return "http://localhost:" + port(app);
    }

    String pspUrl() {
        return url(psp);
    }

    String switchUrl() {
        return url(switchApp);
    }

    /** PSP in front of the switch that only makes the first attempt (no resolution). */
    String rawPspUrl() {
        return url(rawPsp);
    }

    String rawSwitchUrl() {
        return url(rawSwitch);
    }

    String bankAUrl() {
        return url(bankA);
    }

    String bankBUrl() {
        return url(bankB);
    }

    String bankCUrl() {
        return url(bankC);
    }

    /** A bean from the main switch, e.g. its TransactionService (to set up a "crashed" payment). */
    <T> T switchBean(Class<T> type) {
        return switchApp.getBean(type);
    }

    /** Sets chaos on one bank, e.g. chaos(bankAUrl(), Map.of("errorRate", 1.0)). */
    void chaos(String bankUrl, Map<String, ?> settings) {
        http.post().uri(bankUrl + "/admin/chaos").body(settings).retrieve().toBodilessEntity();
    }

    /** All rates back to 0 on every bank. */
    void chaosOff() {
        chaos(bankAUrl(), Map.of());
        chaos(bankBUrl(), Map.of());
        chaos(bankCUrl(), Map.of());
    }

    /** Direct SQL access to a bank's database, for checks no API offers (e.g. total money). */
    JdbcClient bankADb() {
        return bankA.getBean(JdbcClient.class);
    }

    JdbcClient bankBDb() {
        return bankB.getBean(JdbcClient.class);
    }

    JdbcClient bankCDb() {
        return bankC.getBean(JdbcClient.class);
    }

    /** Every bank's database, by bank code. */
    Map<String, JdbcClient> bankDbs() {
        return Map.of("BANKA", bankADb(), "BANKB", bankBDb(), "BANKC", bankCDb());
    }

    /** Runs each bank's own ledger audit (books balance, nobody negative, journal = balances). */
    void auditAllBanks() {
        for (ConfigurableApplicationContext bank : List.of(bankA, bankB, bankC)) {
            bank.getBean(LedgerAuditService.class).check();
        }
    }

    @Override
    public void close() {
        for (ConfigurableApplicationContext app : new ConfigurableApplicationContext[] {
                rawPsp, rawSwitch, psp, switchApp, bankC, bankB, bankA}) {
            if (app != null && app.isActive()) {
                app.close();
            }
        }
        postgres.stop();
    }
}
