package com.payswitch.switchservice.routing;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A Virtual Payment Address such as ravi@banka: "user" is who, "handle" is which bank.
 * If a Vpa object exists, its format is valid.
 */
public record Vpa(String user, String handle) {

    // user: 1-50 letters, digits, dot, dash, underscore.  handle: 2-13 letters.  Total <= 64.
    private static final Pattern FORMAT = Pattern.compile("^([a-z0-9._-]{1,50})@([a-z]{2,13})$");

    /**
     * @throws IllegalArgumentException if the text is not user@handle
     */
    public static Vpa parse(String text) {
        if (text == null) {
            throw new IllegalArgumentException("VPA must not be null");
        }
        // VPAs are not case sensitive: Ravi@BankA and ravi@banka are the same address.
        Matcher m = FORMAT.matcher(text.trim().toLowerCase(Locale.ROOT));
        if (!m.matches()) {
            throw new IllegalArgumentException("VPA must look like user@handle, got: " + text);
        }
        return new Vpa(m.group(1), m.group(2));
    }

    /** The full address, e.g. ravi@banka. */
    public String address() {
        return user + "@" + handle;
    }
}
