package com.payswitch.common.bank;

/** Body of GET /accounts/{vpa}/balance. Balance in paise, as the customer reads it. */
public record BalanceResponse(String vpa, long balance) {
}
