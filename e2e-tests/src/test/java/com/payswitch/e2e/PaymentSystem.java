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
 *   PSP  ->  Switch  ->  Bank A
 *                    ->  Bank B        one Postgres container, three databases
 * </pre>
 *
 * Each service is a separate Spring application with its own random HTTP port, and they talk
 * to each other over real HTTP, exactly like in Docker Compose.
 *
 * <p>Started ONCE and shared by every e2e test class ({@link #shared()}); stopped when the test
 * JVM exits.
 *
 * <p>Why every setting is passed explicitly: all four services are on one classpath here, and
 * each has a file called application.yml. Only one of those files can win, so we do not rely
 * on any of them. Command-line arguments override every file.
 */
final class PaymentSystem implements AutoCloseable {

    /** Short timeouts keep the chaos tests fast. The switch gives up on a bank after 1 s... */
    static final String SWITCH_READ_TIMEOUT = "1s";
    /** ...and a dropped response stays silent 2 s, so the switch always gives up first. */
    static final String DROP_RESPONSE_SLEEP = "2s";

    private static PaymentSystem instance;

    private final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));
    private final RestClient http = RestClient.create();

    private ConfigurableApplicationContext bankA;
    private ConfigurableApplicationContext bankB;
    private ConfigurableApplicationContext switchApp;
    private ConfigurableApplicationContext psp;

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
            s.execute("CREATE DATABASE banka");
            s.execute("CREATE DATABASE bankb");
            s.execute("CREATE DATABASE switch");
        }

        // Start order = dependency order: banks first, then the switch, then the PSP.
        bankA = bank("BANKA", "banka");
        bankB = bank("BANKB", "bankb");
        switchApp = run(SwitchServiceApplication.class, withDatabase("switch",
                "--spring.application.name=switch-service",
                "--spring.flyway.locations=classpath:db/switch/migration",
                "--switch.http.connect-timeout=2s",
                "--switch.http.read-timeout=" + SWITCH_READ_TIMEOUT,
                "--switch.banks.banka.code=BANKA",
                "--switch.banks.banka.url=" + bankAUrl(),
                "--switch.banks.bankb.code=BANKB",
                "--switch.banks.bankb.url=" + bankBUrl()));
        psp = run(PspServiceApplication.class, List.of(
                "--spring.application.name=psp-service",
                "--psp.switch-url=" + switchUrl(),
                "--psp.http.connect-timeout=2s",
                "--psp.http.read-timeout=20s",
                // The PSP has no database. On this shared classpath Spring would still try to
                // create one because the JDBC jars are present, so switch that off.
                "--spring.autoconfigure.exclude="
                        + "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration",
                "--spring.flyway.enabled=false"));

        // Warm up: the very first request to a fresh app is slow (connection pools, class
        // loading). Do it now, so no test's first bank call trips the 1 s timeout.
        for (String url : List.of(bankAUrl() + "/accounts/ravi@banka/balance",
                bankBUrl() + "/accounts/anu@bankb/balance", pspUrl() + "/actuator/health")) {
            http.get().uri(url).retrieve().toBodilessEntity();
        }
        http.get().uri(switchUrl() + "/actuator/health").retrieve().toBodilessEntity();
    }

    private ConfigurableApplicationContext bank(String code, String database) {
        return run(BankServiceApplication.class, withDatabase(database,
                "--spring.application.name=bank-" + code,
                "--spring.profiles.active=chaos",               // /admin/chaos exists, all rates 0
                "--bank.chaos.drop-response-sleep=" + DROP_RESPONSE_SLEEP,
                "--bank.code=" + code,
                "--spring.flyway.locations=classpath:db/bank/migration,classpath:db/bank/seed/" + code));
    }

    private List<String> withDatabase(String database, String... more) {
        List<String> args = new ArrayList<>(List.of(more));
        args.add("--spring.datasource.url=jdbc:postgresql://" + postgres.getHost() + ":"
                + postgres.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT) + "/" + database);
        args.add("--spring.datasource.username=" + postgres.getUsername());
        args.add("--spring.datasource.password=" + postgres.getPassword());
        return args;
    }

    private static ConfigurableApplicationContext run(Class<?> app, List<String> args) {
        List<String> all = new ArrayList<>(args);
        all.add("--server.port=0");                       // 0 = any free port
        all.add("--spring.main.banner-mode=off");
        return new SpringApplicationBuilder(app).run(all.toArray(String[]::new));
    }

    private static String url(ConfigurableApplicationContext app) {
        return "http://localhost:" + ((ServletWebServerApplicationContext) app).getWebServer().getPort();
    }

    String pspUrl() {
        return url(psp);
    }

    String switchUrl() {
        return url(switchApp);
    }

    String bankAUrl() {
        return url(bankA);
    }

    String bankBUrl() {
        return url(bankB);
    }

    /** Sets chaos on one bank, e.g. chaos(bankAUrl(), Map.of("errorRate", 1.0)). */
    void chaos(String bankUrl, Map<String, ?> settings) {
        http.post().uri(bankUrl + "/admin/chaos").body(settings).retrieve().toBodilessEntity();
    }

    /** All rates back to 0 on both banks. */
    void chaosOff() {
        chaos(bankAUrl(), Map.of());
        chaos(bankBUrl(), Map.of());
    }

    /** Direct SQL access to a bank's database, for checks no API offers (e.g. total money). */
    JdbcClient bankADb() {
        return bankA.getBean(JdbcClient.class);
    }

    JdbcClient bankBDb() {
        return bankB.getBean(JdbcClient.class);
    }

    /** Runs each bank's own ledger audit (books balance, nobody negative, journal = balances). */
    void auditBothBanks() {
        bankA.getBean(LedgerAuditService.class).check();
        bankB.getBean(LedgerAuditService.class).check();
    }

    @Override
    public void close() {
        for (ConfigurableApplicationContext app : new ConfigurableApplicationContext[] {psp, switchApp, bankB, bankA}) {
            if (app != null && app.isActive()) {
                app.close();
            }
        }
        postgres.stop();
    }
}
