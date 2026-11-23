package com.payswitch.switchservice.money;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.payswitch.common.bank.BankSummary;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Phase 12: TOTAL = customer money + in transit, and it stays the same at every step of a payment and
 * of settlement. Ravi (Bank A) pays Anu (Bank B) Rs 200; both banks start with Rs 1,000 of customer money.
 */
class MoneySummaryTest {

    private static final long RS_200 = 20_000;

    private static long total(BankSummary a, BankSummary b) {
        return MoneySummary.of(List.of(a, b), Map.of()).total();
    }

    @Test
    void totalIsTheSameAtEveryStepOfAPaymentAndOfSettlement() {
        long start = total(new BankSummary("BANKA", 100_000, 0, 0, 1), new BankSummary("BANKB", 100_000, 0, 0, 1));

        // Debited at Bank A: the Rs 200 left Ravi and sits in Bank A's settlement account.
        long debited = total(new BankSummary("BANKA", 80_000, RS_200, 0, 1), new BankSummary("BANKB", 100_000, 0, 0, 1));
        // Credited at Bank B: Bank B is owed Rs 200 by the switch.
        long credited = total(new BankSummary("BANKA", 80_000, RS_200, 0, 1),
                new BankSummary("BANKB", 120_000, -RS_200, 0, 1));
        // Settlement, half done: Bank A has paid its Rs 200 from its central reserve; Bank B not yet told.
        long halfSettled = total(new BankSummary("BANKA", 80_000, 0, -RS_200, 1),
                new BankSummary("BANKB", 120_000, -RS_200, 0, 1));
        // Settled: Bank B's reserve went up by Rs 200 and its settlement account is back to 0.
        long settled = total(new BankSummary("BANKA", 80_000, 0, -RS_200, 1),
                new BankSummary("BANKB", 120_000, 0, RS_200, 1));

        assertEquals(200_000, start);
        assertEquals(start, debited);
        assertEquals(start, credited);
        assertEquals(start, halfSettled, "subtracting the reserve keeps TOTAL steady even mid-settlement");
        assertEquals(start, settled);
    }

    @Test
    void inTransitIsWhatHasLeftAPayerButNotReachedAPayee() {
        MoneySummary s = MoneySummary.of(List.of(new BankSummary("BANKA", 80_000, RS_200, 0, 2),
                new BankSummary("BANKB", 100_000, 0, 0, 3)), Map.of("DEBITED", 1L));

        assertEquals(180_000, s.customerMoney());
        assertEquals(RS_200, s.inTransit());
        assertEquals(200_000, s.total());
        assertEquals(RS_200, s.banks().get(0).inTransit());
        assertEquals(1L, s.txnsByState().get("DEBITED"));
    }
}
