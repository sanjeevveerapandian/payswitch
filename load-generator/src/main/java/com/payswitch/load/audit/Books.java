package com.payswitch.load.audit;

import java.util.List;
import java.util.Map;

/**
 * Everything the auditor read from the four databases, as plain data. The checks
 * ({@link MoneyChecks}) only look at this: no SQL in the checks, so each check can be tested by
 * building Books by hand with one bug planted in it.
 *
 * <p>Money values are paise, as the CUSTOMER sees them (positive = money the customer has),
 * except where a field says "raw".
 */
public record Books(List<SwitchTxn> txns, List<BankBooks> banks, CentralBooks central,
                    Map<String, String> latestReconCategory) {

    /** One row of the switch's transactions table. VPAs in lower case. */
    public record SwitchTxn(String txnId, String payerVpa, String payeeVpa, long amount, String state) {
    }

    /** A customer account at one bank. */
    public record Customer(String bank, String vpa, long balance, long opening) {
    }

    /**
     * One bank's books.
     *
     * @param opCounts           txnId -> op type (DEBIT / CREDIT / REVERSE) -> how many journal entries
     * @param customerEffects    txnId -> customer VPA -> net change to that customer's money, from every
     *                           journal entry that is not an OPENING deposit
     * @param switchSettlement   what this bank owes the switch (natural balance of SWITCH_SETTLEMENT)
     * @param centralReserveRaw  raw balance of CENTRAL_BANK_RESERVE (an asset: raw = natural)
     * @param rawBalanceSum      sum of every account's raw balance; 0 when the books balance
     * @param unbalancedEntries  journal entries whose lines do not sum to 0
     * @param accountsOffJournal accounts whose stored balance differs from the sum of their lines
     */
    public record BankBooks(String bank, List<Customer> customers, Map<String, Map<String, Integer>> opCounts,
                            Map<String, Map<String, Long>> customerEffects, long switchSettlement,
                            long centralReserveRaw, long rawBalanceSum, int unbalancedEntries,
                            int accountsOffJournal) {
    }

    /**
     * The central bank's ledger (kept by the switch) and the settlement cycles.
     *
     * @param accountRaw       RESERVE-BANKA etc. -> stored raw balance
     * @param accountLineSums  account -> sum of its entry lines (must equal accountRaw)
     * @param netSumByCycle    for every cycle that is not FAILED: sum of its net positions (must be 0)
     */
    public record CentralBooks(Map<String, Long> accountRaw, Map<String, Long> accountLineSums,
                               int unbalancedEntries, Map<Long, Long> netSumByCycle, int failedCycles) {
    }
}
