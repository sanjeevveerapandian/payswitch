package com.payswitch.switchservice.recon;

import com.payswitch.common.bank.OpType;
import com.payswitch.common.bank.ReconEntry;
import com.payswitch.common.bank.ReconStatus;
import com.payswitch.switchservice.domain.TxnState;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Pure rule: compare what the switch believes with what the banks say. No database, no HTTP.
 *
 * <p>The comparison is about NET money, per side:
 * <ul>
 *   <li>payer side = debited minus reversed, at the payer's bank</li>
 *   <li>payee side = credited, at the payee's bank</li>
 * </ul>
 * What each final switch state promises:
 * <pre>
 *   SUCCESS   payer side = amount, payee side = amount
 *   FAILED    payer side = 0,      payee side = 0     (no debit, a declined one, or a fenced one)
 *   REVERSED  payer side = 0,      payee side = 0     (debit + reverse, or a NO_DEBIT reverse)
 * </pre>
 */
public final class ReconMatcher {

    private ReconMatcher() {
    }

    public record Verdict(ReconCategory category, String bankView) {
    }

    /**
     * @param payerEntries everything the payer's bank reported for this txnId (empty if none)
     * @param payeeEntries everything the payee's bank reported for this txnId (empty if none)
     */
    public static Verdict compare(TxnState state, long amount, String payerBank, List<ReconEntry> payerEntries,
                                  String payeeBank, List<ReconEntry> payeeEntries) {
        String view = describe(payerBank, payerEntries) + " | " + describe(payeeBank, payeeEntries);
        if (state != TxnState.SUCCESS && state != TxnState.FAILED && state != TxnState.REVERSED) {
            return new Verdict(ReconCategory.UNFINISHED, view);
        }
        long payerSide = moved(payerEntries, OpType.DEBIT) - moved(payerEntries, OpType.REVERSE);
        long payeeSide = moved(payeeEntries, OpType.CREDIT);
        long expected = state == TxnState.SUCCESS ? amount : 0;

        ReconCategory payer = side(expected, payerSide);
        ReconCategory payee = side(expected, payeeSide);
        ReconCategory category = payer != ReconCategory.MATCHED ? payer : payee;
        return new Verdict(category, view);
    }

    /** Paise that really moved for this operation (only SUCCESS entries move money). */
    public static long moved(List<ReconEntry> entries, OpType op) {
        return entries.stream()
                .filter(e -> e.op() == op && e.status() == ReconStatus.SUCCESS)
                .mapToLong(ReconEntry::amount)
                .sum();
    }

    private static ReconCategory side(long expected, long actual) {
        if (expected == actual) {
            return ReconCategory.MATCHED;
        }
        if (actual == 0) {
            return ReconCategory.SWITCH_ONLY;      // switch expected money to move; bank shows none
        }
        if (expected == 0) {
            return ReconCategory.BANK_ONLY;        // bank moved money the switch says never moved
        }
        return ReconCategory.AMOUNT_MISMATCH;
    }

    /** e.g. "BANKA: DEBIT SUCCESS 20000, REVERSE SUCCESS 20000" or "BANKB: (nothing)". */
    public static String describe(String bank, List<ReconEntry> entries) {
        String what = entries.isEmpty() ? "(nothing)" : entries.stream()
                .map(e -> e.op() + " " + e.status() + " " + e.amount())
                .collect(Collectors.joining(", "));
        String text = (bank == null ? "?" : bank) + ": " + what;
        return text.length() > 240 ? text.substring(0, 240) : text;
    }
}
