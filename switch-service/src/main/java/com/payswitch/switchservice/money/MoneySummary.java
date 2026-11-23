package com.payswitch.switchservice.money;

import com.payswitch.common.bank.BankSummary;
import java.util.List;
import java.util.Map;

/**
 * Where all the money in the system is, right now. All amounts in paise.
 *
 * <p>TOTAL = customer money + in transit. It must never change: payments only move money between
 * these two buckets, and settlement only moves it within "in transit". If it ever changes, money
 * was created or destroyed (the UI flashes it red).
 *
 * <p>Why "in transit" subtracts the central reserve: after Ravi's Rs 200, Bank A's SWITCH_SETTLEMENT
 * is +200 and Bank B's is -200. Settlement zeroes them one bank at a time, moving the same amount
 * into each bank's CENTRAL_BANK_RESERVE. Between "Bank A settled" and "Bank B settled", the plain
 * sum of SWITCH_SETTLEMENT is briefly off by 200; SWITCH_SETTLEMENT minus reserve never is.
 *
 * @param inTransit   per bank: switchSettlement - centralReserve, summed. Payers debited whose payees
 *                    are not yet credited (or reversed); 0 when nothing is in flight.
 * @param txnsByState how many payments the switch has in each state
 */
public record MoneySummary(List<BankMoney> banks, long customerMoney, long inTransit, long total,
                           Map<String, Long> txnsByState) {

    public record BankMoney(String bank, long customerMoney, long switchSettlement, long centralReserve,
                            long inTransit, int customerAccounts) {

        static BankMoney of(BankSummary s) {
            return new BankMoney(s.bankCode(), s.customerMoney(), s.switchSettlement(), s.centralReserve(),
                    Math.subtractExact(s.switchSettlement(), s.centralReserve()), s.customerAccounts());
        }
    }

    public static MoneySummary of(List<BankSummary> banks, Map<String, Long> txnsByState) {
        List<BankMoney> rows = banks.stream().map(BankMoney::of).toList();
        long customers = 0;
        long inTransit = 0;
        for (BankMoney b : rows) {
            customers = Math.addExact(customers, b.customerMoney());
            inTransit = Math.addExact(inTransit, b.inTransit());
        }
        return new MoneySummary(rows, customers, inTransit, Math.addExact(customers, inTransit), txnsByState);
    }
}
