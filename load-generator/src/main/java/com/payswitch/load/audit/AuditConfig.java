package com.payswitch.load.audit;

import java.time.Duration;
import java.util.Map;
import java.util.OptionalLong;

/**
 * Where everything is, and how patient to be.
 *
 * @param jdbcBase       e.g. jdbc:postgresql://localhost:5433/ (the database name is appended)
 * @param bankDatabases  bank code -> database name, e.g. BANKA -> banka
 * @param prepare        true: first wait for the resolver, run recon and settle (the normal case).
 *                       false: only read and check, change nothing.
 * @param waitTimeout    how long to wait for the resolver, recon and settlement
 * @param expectedTotal  total customer money measured before the run, if known
 * @param reconCutoff    the switch's switch.recon.cutoff: recon skips payments younger than this, so
 *                       the auditor waits until the newest payment is this old before reconciling
 */
public record AuditConfig(String switchUrl, String jdbcBase, String user, String password,
                          Map<String, String> bankDatabases, String switchDatabase, boolean prepare,
                          Duration waitTimeout, OptionalLong expectedTotal, Duration reconCutoff) {

    /** The Docker Compose setup: Postgres on localhost:5433, switch on 8083. */
    public static AuditConfig dockerDefaults() {
        return new AuditConfig("http://localhost:8083", "jdbc:postgresql://localhost:5433/", "payswitch", "payswitch",
                Map.of("BANKA", "banka", "BANKB", "bankb", "BANKC", "bankc"), "switch", true,
                Duration.ofMinutes(30), OptionalLong.empty(), Duration.ofSeconds(10));
    }

    public AuditConfig withExpectedTotal(long total) {
        return new AuditConfig(switchUrl, jdbcBase, user, password, bankDatabases, switchDatabase, prepare,
                waitTimeout, OptionalLong.of(total), reconCutoff);
    }
}
