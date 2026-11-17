package com.payswitch.switchservice.recon;

import static com.payswitch.common.bank.OpType.CREDIT;
import static com.payswitch.common.bank.OpType.DEBIT;
import static com.payswitch.common.bank.OpType.REVERSE;
import static com.payswitch.common.bank.ReconStatus.DECLINED;
import static com.payswitch.common.bank.ReconStatus.FENCED;
import static com.payswitch.common.bank.ReconStatus.NO_DEBIT;
import static com.payswitch.common.bank.ReconStatus.SUCCESS;
import static com.payswitch.switchservice.recon.ReconCategory.AMOUNT_MISMATCH;
import static com.payswitch.switchservice.recon.ReconCategory.BANK_ONLY;
import static com.payswitch.switchservice.recon.ReconCategory.MATCHED;
import static com.payswitch.switchservice.recon.ReconCategory.SWITCH_ONLY;
import static com.payswitch.switchservice.recon.ReconCategory.UNFINISHED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.payswitch.common.bank.OpType;
import com.payswitch.common.bank.ReconEntry;
import com.payswitch.common.bank.ReconStatus;
import com.payswitch.switchservice.domain.TxnState;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The comparison rule on its own: switch state + amount vs. what each bank reports. */
class ReconMatcherTest {

    private static final long RS_200 = 20_000;

    private static ReconEntry e(OpType op, ReconStatus status, long amount) {
        return new ReconEntry("T1", op, status, amount, Instant.parse("2026-11-17T10:00:00Z"));
    }

    private static ReconCategory verdict(TxnState state, List<ReconEntry> payer, List<ReconEntry> payee) {
        return ReconMatcher.compare(state, RS_200, "BANKA", payer, "BANKB", payee).category();
    }

    // ---- what each final state promises, when the banks agree ---------------------------

    @Test
    void successMatchesADebitAndACreditOfTheSameAmount() {
        assertEquals(MATCHED, verdict(TxnState.SUCCESS,
                List.of(e(DEBIT, SUCCESS, RS_200)), List.of(e(CREDIT, SUCCESS, RS_200))));
    }

    @Test
    void failedMatchesNoDebitADeclinedDebitOrAFencedDebit() {
        assertEquals(MATCHED, verdict(TxnState.FAILED, List.of(), List.of()));
        assertEquals(MATCHED, verdict(TxnState.FAILED, List.of(e(DEBIT, DECLINED, 0)), List.of()));
        assertEquals(MATCHED, verdict(TxnState.FAILED, List.of(e(DEBIT, FENCED, 0)), List.of()));
    }

    @Test
    void reversedMatchesADebitPlusAReverseOrANoDebitReverse() {
        assertEquals(MATCHED, verdict(TxnState.REVERSED,
                List.of(e(DEBIT, SUCCESS, RS_200), e(REVERSE, SUCCESS, RS_200)), List.of(e(CREDIT, FENCED, 0))));
        assertEquals(MATCHED, verdict(TxnState.REVERSED,
                List.of(e(REVERSE, NO_DEBIT, 0), e(DEBIT, FENCED, 0)), List.of()));
    }

    // ---- disagreements ------------------------------------------------------------------

    @Test
    void successButTheBankHasNoCreditIsSwitchOnly() {
        assertEquals(SWITCH_ONLY, verdict(TxnState.SUCCESS, List.of(e(DEBIT, SUCCESS, RS_200)), List.of()));
    }

    @Test
    void successButNoDebitAtAllIsSwitchOnly() {
        assertEquals(SWITCH_ONLY, verdict(TxnState.SUCCESS, List.of(), List.of(e(CREDIT, SUCCESS, RS_200))));
    }

    @Test
    void failedButTheBankDebitedIsBankOnly() {
        assertEquals(BANK_ONLY, verdict(TxnState.FAILED, List.of(e(DEBIT, SUCCESS, RS_200)), List.of()));
    }

    @Test
    void reversedButTheReverseNeverHappenedIsBankOnly() {
        // The switch says Ravi has his money back; Bank A shows him still debited.
        assertEquals(BANK_ONLY, verdict(TxnState.REVERSED, List.of(e(DEBIT, SUCCESS, RS_200)), List.of()));
    }

    @Test
    void creditForADifferentAmountIsAmountMismatch() {
        assertEquals(AMOUNT_MISMATCH, verdict(TxnState.SUCCESS,
                List.of(e(DEBIT, SUCCESS, RS_200)), List.of(e(CREDIT, SUCCESS, 19_900))));
    }

    @Test
    void stillMidPaymentIsUnfinished() {
        for (TxnState s : List.of(TxnState.INITIATED, TxnState.DEBIT_SENT, TxnState.DEBITED,
                TxnState.CREDIT_SENT, TxnState.REVERSAL_SENT)) {
            assertEquals(UNFINISHED, verdict(s, List.of(), List.of()), s.name());
        }
    }

    @Test
    void onlySuccessfulEntriesCountAsMovedMoney() {
        List<ReconEntry> entries = List.of(e(DEBIT, SUCCESS, 100), e(DEBIT, DECLINED, 0), e(DEBIT, FENCED, 0),
                e(CREDIT, SUCCESS, 50));
        assertEquals(100, ReconMatcher.moved(entries, DEBIT));
        assertEquals(50, ReconMatcher.moved(entries, CREDIT));
        assertEquals(0, ReconMatcher.moved(entries, REVERSE));
    }

    @Test
    void theBankViewSaysWhatEachBankReported() {
        String view = ReconMatcher.compare(TxnState.SUCCESS, RS_200, "BANKA", List.of(e(DEBIT, SUCCESS, RS_200)),
                "BANKB", List.of()).bankView();
        assertEquals("BANKA: DEBIT SUCCESS 20000 | BANKB: (nothing)", view);
    }

    @Test
    void onlyMatchedAndResolvedDeemedAreNotExceptions() {
        assertFalse(MATCHED.isException());
        assertFalse(ReconCategory.RESOLVED_DEEMED.isException());
        for (ReconCategory c : List.of(SWITCH_ONLY, BANK_ONLY, AMOUNT_MISMATCH, UNFINISHED,
                ReconCategory.DEEMED_UNRESOLVED)) {
            assertTrue(c.isException(), c.name());
        }
    }
}
