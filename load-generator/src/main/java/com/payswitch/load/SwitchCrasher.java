package com.payswitch.load;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Kills the switch in the middle of the run and starts it again. An interface, so a test can plug
 * in "restart the in-process switch" instead of Docker.
 */
public interface SwitchCrasher {

    /** Kill the switch, wait, start it again. Logs both moments. */
    void crashAndRestart(Consumer<String> log) throws Exception;

    /**
     * The real thing: "docker compose kill switch" (SIGKILL, like a power cut: no graceful
     * shutdown, requests in flight are cut off), 5 seconds of nothing, "docker compose start switch".
     */
    static SwitchCrasher dockerCompose(File composeDir, long downMillis) {
        return log -> {
            log.accept("CRASH: docker compose kill switch");
            run(composeDir, List.of("docker", "compose", "kill", "switch"));
            log.accept("CRASH: switch killed; waiting " + downMillis + " ms");
            Thread.sleep(downMillis);
            log.accept("RESTART: docker compose start switch");
            run(composeDir, List.of("docker", "compose", "start", "switch"));
            log.accept("RESTART: switch container started (it needs a few seconds to boot and run its startup recovery)");
        };
    }

    private static void run(File dir, List<String> command) throws IOException, InterruptedException {
        Process p = new ProcessBuilder(command).directory(dir).redirectErrorStream(true).start();
        String output = new String(p.getInputStream().readAllBytes());
        if (!p.waitFor(60, TimeUnit.SECONDS) || p.exitValue() != 0) {
            throw new IOException(String.join(" ", command) + " failed: " + output);
        }
    }
}
