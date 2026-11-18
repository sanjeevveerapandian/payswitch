package com.payswitch.bank;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.payswitch.bank.settlement.SettlementAdviceConflictException;
import com.payswitch.bank.settlement.SettlementAdviceService;
import com.payswitch.common.bank.DebitRequest;
import com.payswitch.common.settlement.SettlementAdvice;
import com.payswitch.common.settlement.SettlementAdviceResponse;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

/**
 * POST /settlement/advice: clear SWITCH_SETTLEMENT against CENTRAL_BANK_RESERVE, once per cycle.
 * Tests share Bank A's database, so every test uses its own cycle ids and checks DIFFERENCES.
 */
class SettlementAdviceTest extends BankIntegrationTest {

    /** Cycle ids no other test uses. */
    private static final AtomicLong NEXT_CYCLE = new AtomicLong(System.nanoTime() % 1_000_000_000L);

    @Autowired
    private SettlementAdviceService advices;

    @LocalServerPort
    private int port;

    private long reserve() {
        return jdbc.sql("SELECT raw_balance FROM accounts WHERE id = 'CENTRAL_BANK_RESERVE'")
                .query(Long.class).single();       // an ASSET: natural balance = raw balance
    }

    @Test
    void aPayingBankClearsWhatItOwesAndItsReserveGoesDown() {
        // Ravi pays Rs 200 out of Bank A: Bank A now owes the switch Rs 200.
        String ravi = newCustomer(100_000);
        bank.debit(new DebitRequest(newTxnId(), ravi, 20_000));
        long owedBefore = settlement();
        long reserveBefore = reserve();
        long cycle = NEXT_CYCLE.incrementAndGet();

        advices.apply(new SettlementAdvice(cycle, -20_000));

        assertEquals(owedBefore - 20_000, settlement());       // the Rs 200 debt is cleared...
        assertEquals(reserveBefore - 20_000, reserve());       // ...paid from its central bank reserve
        assertEquals(1, journalEntriesFor("CYCLE-" + cycle));
    }

    @Test
    void aReceivingBankIsPaidIntoItsReserve() {
        long owedBefore = settlement();
        long reserveBefore = reserve();

        advices.apply(new SettlementAdvice(NEXT_CYCLE.incrementAndGet(), 40_000));

        assertEquals(owedBefore + 40_000, settlement());       // the switch owed it Rs 400: no longer
        assertEquals(reserveBefore + 40_000, reserve());
    }

    @Test
    void theSameAdviceTwiceChangesTheBooksOnce() {
        long cycle = NEXT_CYCLE.incrementAndGet();
        SettlementAdvice advice = new SettlementAdvice(cycle, -6_000);

        SettlementAdviceResponse first = advices.apply(advice);
        long owed = settlement();
        long reserve = reserve();
        SettlementAdviceResponse second = advices.apply(advice);

        assertEquals(first, second);                           // same answer
        assertEquals(owed, settlement());                      // nothing moved the second time
        assertEquals(reserve, reserve());
        assertEquals(1, journalEntriesFor("CYCLE-" + cycle));
    }

    @Test
    void theSameCycleWithADifferentAmountIsRefusedAndChangesNothing() {
        long cycle = NEXT_CYCLE.incrementAndGet();
        advices.apply(new SettlementAdvice(cycle, -6_000));
        long owed = settlement();

        assertThrows(SettlementAdviceConflictException.class,
                () -> advices.apply(new SettlementAdvice(cycle, -9_000)));

        assertEquals(owed, settlement());
        assertEquals(-6_000L, advices.appliedAmount(cycle).orElseThrow());
    }

    @Test
    void aZeroPositionIsRecordedButPostsNoEntry() {
        long cycle = NEXT_CYCLE.incrementAndGet();

        advices.apply(new SettlementAdvice(cycle, 0));

        assertEquals(0L, advices.appliedAmount(cycle).orElseThrow());
        assertEquals(0, journalEntriesFor("CYCLE-" + cycle));
    }

    @Test
    void overHttpARetryGetsTheSameAnswerAndAConflictIs409() {
        RestClient http = RestClient.create("http://localhost:" + port);
        long cycle = NEXT_CYCLE.incrementAndGet();
        SettlementAdvice advice = new SettlementAdvice(cycle, 12_345);

        SettlementAdviceResponse first = http.post().uri("/settlement/advice").body(advice).retrieve()
                .body(SettlementAdviceResponse.class);
        SettlementAdviceResponse retry = http.post().uri("/settlement/advice").body(advice).retrieve()
                .body(SettlementAdviceResponse.class);

        assertEquals(new SettlementAdviceResponse(cycle, 12_345), first);
        assertEquals(first, retry);
        HttpClientErrorException e = assertThrows(HttpClientErrorException.class, () -> http.post()
                .uri("/settlement/advice").body(new SettlementAdvice(cycle, 1)).retrieve().toBodilessEntity());
        assertEquals(HttpStatus.CONFLICT, e.getStatusCode());
    }
}
