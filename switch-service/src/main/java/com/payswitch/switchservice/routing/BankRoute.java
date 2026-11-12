package com.payswitch.switchservice.routing;

import java.net.URI;

/**
 * Where to find one bank.
 *
 * @param code the bank's code, e.g. BANKA
 * @param url  base URL of that bank's API, e.g. http://localhost:8081
 */
public record BankRoute(String code, URI url) {

    public BankRoute {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("bank code must not be blank");
        }
        if (url == null) {
            throw new IllegalArgumentException("bank url must not be null for " + code);
        }
    }
}
