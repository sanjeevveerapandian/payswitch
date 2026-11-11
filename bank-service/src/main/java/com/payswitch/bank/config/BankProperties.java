package com.payswitch.bank.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Which bank this running copy of the app is: BANKA or BANKB. Bound from "bank.code".
 * The code also picks the seed folder (db/seed/BANKA or db/seed/BANKB).
 */
@ConfigurationProperties("bank")
public record BankProperties(String code) {

    public BankProperties {
        if (code == null || !code.matches("BANK[A-Z]")) {
            throw new IllegalArgumentException("bank.code must look like BANKA or BANKB, got: " + code);
        }
    }
}
