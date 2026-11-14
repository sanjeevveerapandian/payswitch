package com.payswitch.bank.chaos;

/**
 * How unreliable the bank should pretend to be. Body of POST /admin/chaos. Missing fields are 0.
 *
 * <p>For every /debit, /credit or /reverse the bank draws ONE random number between 0 and 1
 * and walks along the rates in this order:
 *
 * <pre>
 *  0 ── declineRate ──┬── errorRate ──┬── dropResponseRate ──┬──────── no chaos ──────── 1
 *       DECLINE       │     ERROR     │    DROP_RESPONSE     │        PROCEED
 * </pre>
 *
 * So the rates must add up to at most 1. With declineRate 0.2 and errorRate 0.1, about 20% of
 * calls are declined, 10% fail with HTTP 500, and 70% behave normally.
 *
 * @param declineRate      answer DECLINED without doing anything
 * @param errorRate        answer HTTP 500 without doing anything
 * @param delayMs          sleep this long BEFORE handling every call (whatever the dice say)
 * @param dropResponseRate do the operation, commit it, then go silent past the caller's timeout
 * @param seed             seed for the dice; the same seed gives the same sequence of decisions.
 *                         null = a different sequence every time
 * @param statusErrorRate  POST /status-check only: answer HTTP 500 without looking anything up.
 *                         Rolled on separate dice, so it never shifts the sequence above.
 */
public record ChaosSettings(double declineRate, double errorRate, long delayMs, double dropResponseRate,
                            Long seed, double statusErrorRate) {

    public static final ChaosSettings NONE = new ChaosSettings(0, 0, 0, 0, null, 0);

    /** Settings without status-check chaos (the Phase 5 shape). */
    public ChaosSettings(double declineRate, double errorRate, long delayMs, double dropResponseRate, Long seed) {
        this(declineRate, errorRate, delayMs, dropResponseRate, seed, 0);
    }

    public ChaosSettings {
        requireRate(declineRate, "declineRate");
        requireRate(errorRate, "errorRate");
        requireRate(dropResponseRate, "dropResponseRate");
        requireRate(statusErrorRate, "statusErrorRate");
        if (declineRate + errorRate + dropResponseRate > 1.0) {
            throw new IllegalArgumentException("declineRate + errorRate + dropResponseRate must be at most 1");
        }
        if (delayMs < 0 || delayMs > 60_000) {
            throw new IllegalArgumentException("delayMs must be between 0 and 60000, got " + delayMs);
        }
    }

    private static void requireRate(double rate, String name) {
        if (!(rate >= 0.0 && rate <= 1.0)) {          // also rejects NaN
            throw new IllegalArgumentException(name + " must be between 0 and 1, got " + rate);
        }
    }
}
