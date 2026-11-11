package com.payswitch.bank;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.payswitch.common.bank.BankResponse;
import com.payswitch.common.bank.BankResult;
import com.payswitch.common.bank.BankTxnRequest;
import com.payswitch.common.bank.DeclineReason;
import java.util.List;
import org.junit.jupiter.api.Test;

class ConcurrencyTest extends BankIntegrationTest {

    /**
     * Rs 5,000 in the account. 100 threads each try to take Rs 100 (different txnIds) at the
     * same moment. Only 50 can fit. Without SELECT ... FOR UPDATE, threads read the same old
     * balance and overwrite each other (lost update), so more than 50 would "succeed".
     */
    @Test
    void hundredThreadsDebitingRs100FromRs5000() throws Exception {
        String ravi = newCustomer(500_000);
        long settlementBefore = settlement();

        List<BankResponse> answers = IdempotencyTest.runConcurrently(100,
                i -> bank.debit(new BankTxnRequest(newTxnId(), ravi, 10_000)));

        long succeeded = answers.stream().filter(a -> a.result() == BankResult.SUCCESS).count();
        long declined = answers.stream().filter(a -> a.reason() == DeclineReason.INSUFFICIENT_FUNDS).count();
        assertEquals(50, succeeded);
        assertEquals(50, declined);
        assertEquals(0, balance(ravi));
        assertEquals(settlementBefore + 500_000, settlement());
        // @AfterEach audit: books balance and no customer is negative.
    }
}
