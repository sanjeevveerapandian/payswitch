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
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
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
 * <p>Why every setting is passed explicitly: all four services are on one classpath here, and
 * each has a file called application.yml. Only one of those files can win, so we do not rely
 * on any of them. Command-line arguments override every file.
 */
final class PaymentSystem implements AutoCloseable {

    private final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));

    private ConfigurableApplicationContext bankA;
    private ConfigurableApplicationContext bankB;
    private ConfigurableApplicationContext switchApp;
    private ConfigurableApplicationContext psp;

    void start() throws SQLException {
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
                "--switch.http.read-timeout=5s",
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
    }

    private ConfigurableApplicationContext bank(String code, String database) {
        return run(BankServiceApplication.class, withDatabase(database,
                "--spring.application.name=bank-" + code,
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
            if (app != null) {
                app.close();
            }
        }
        postgres.stop();
    }
}
