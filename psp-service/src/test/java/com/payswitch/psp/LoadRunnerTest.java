package com.payswitch.psp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.payswitch.common.pay.PayState;
import com.payswitch.common.pay.ReqPay;
import com.payswitch.common.pay.RespPay;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** Phase 12: the demo's "Run 1,000 payments" button, with the switch faked. */
@SpringBootTest
@AutoConfigureMockMvc
class LoadRunnerTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private LoadRunner runner;

    @MockitoBean
    private SwitchClient switchClient;

    private void waitUntilDone() throws InterruptedException {
        Instant deadline = Instant.now().plusSeconds(20);
        while (runner.status().running() && Instant.now().isBefore(deadline)) {
            Thread.sleep(50);
        }
        assertFalse(runner.status().running(), "the run finished");
    }

    @Test
    void runsEveryPaymentWithinTheLimitAndCountsEachResult() throws Exception {
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger maxInFlight = new AtomicInteger();
        Map<String, Boolean> pairs = new ConcurrentHashMap<>();
        AtomicInteger n = new AtomicInteger();
        when(switchClient.pay(any())).thenAnswer(call -> {
            ReqPay req = call.getArgument(0);
            maxInFlight.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
            try {
                Thread.sleep(5);
                pairs.put(req.payerVpa() + ">" + req.payeeVpa(), true);
                assertNotEquals(req.payerVpa(), req.payeeVpa());
                assertTrue(LoadRunner.CUSTOMERS.contains(req.payerVpa()));
                assertTrue(req.amount() >= 100 && req.amount() <= 20_000, "Rs 1 to Rs 200");
                return new RespPay(req.txnId(), n.incrementAndGet() % 4 == 0 ? PayState.FAILED : PayState.SUCCESS, "x");
            } finally {
                inFlight.decrementAndGet();
            }
        });

        mvc.perform(post("/admin/load").param("count", "200")).andExpect(status().isAccepted());
        waitUntilDone();

        LoadRunner.Status s = runner.status();
        assertEquals(200, s.done());
        assertEquals(Map.of("SUCCESS", 150, "FAILED", 50), s.byResult());
        assertTrue(maxInFlight.get() <= LoadRunner.CONCURRENCY, "never more than 10 at once: " + maxInFlight);
        assertTrue(pairs.size() > 10, "random payers and payees");
        mvc.perform(get("/admin/load")).andExpect(status().isOk());
    }

    @Test
    void aSecondRunWhileOneIsGoingIs409() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        when(switchClient.pay(any())).thenAnswer(call -> {
            release.await(10, TimeUnit.SECONDS);
            return new RespPay(((ReqPay) call.getArgument(0)).txnId(), PayState.SUCCESS, "x");
        });

        mvc.perform(post("/admin/load").param("count", "20")).andExpect(status().isAccepted());
        mvc.perform(post("/admin/load").param("count", "20")).andExpect(status().isConflict());

        release.countDown();
        waitUntilDone();
        assertEquals(20, runner.status().done());
    }

    @Test
    void errorsAreCountedNotThrown() throws Exception {
        when(switchClient.pay(any())).thenThrow(new org.springframework.web.client.ResourceAccessException("down"));

        assertTrue(runner.start(5));
        waitUntilDone();

        assertEquals(Map.of("SWITCH_UNAVAILABLE", 5), runner.status().byResult());
        assertEquals(5, runner.status().done());
        assertTrue(Duration.between(runner.status().startedAt(), runner.status().finishedAt()).toSeconds() < 20);
    }
}
