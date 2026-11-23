package com.payswitch.common.bank;

/**
 * One bank's money at a glance, from GET /admin/summary. All amounts in paise, as natural balances
 * (what the account holder would call "my balance").
 *
 * @param customerMoney     sum of every customer account
 * @param switchSettlement  SWITCH_SETTLEMENT: positive = this bank holds money for the switch
 *                          (payers debited, payees not yet credited elsewhere); negative = it is owed
 * @param centralReserve    CENTRAL_BANK_RESERVE: what settlement has already moved at the central bank
 * @param customerAccounts  how many customer accounts the bank has
 */
public record BankSummary(String bankCode, long customerMoney, long switchSettlement, long centralReserve,
                          int customerAccounts) {
}
