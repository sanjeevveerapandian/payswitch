package com.payswitch.load;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Tiny "--name value" parser. Unknown names are an error, so a typo like --concurrncy 10 fails
 * loudly instead of silently running with the default.
 */
public final class Args {

    private final Map<String, String> values = new HashMap<>();

    public Args(String[] args, Set<String> allowed) {
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (!arg.startsWith("--")) {
                throw new IllegalArgumentException("Expected --name, got: " + arg);
            }
            String name;
            String value;
            int eq = arg.indexOf('=');
            if (eq > 0) {                                     // --count=5000
                name = arg.substring(2, eq);
                value = arg.substring(eq + 1);
            } else {                                          // --count 5000
                name = arg.substring(2);
                if (i + 1 >= args.length) {
                    throw new IllegalArgumentException("Missing value for --" + name);
                }
                value = args[++i];
            }
            if (!allowed.contains(name)) {
                throw new IllegalArgumentException("Unknown option --" + name + ". Allowed: " + List.copyOf(allowed));
            }
            values.put(name, value);
        }
    }

    public String text(String name, String fallback) {
        return values.getOrDefault(name, fallback);
    }

    public boolean has(String name) {
        return values.containsKey(name);
    }

    public int integer(String name, int fallback) {
        return values.containsKey(name) ? Integer.parseInt(values.get(name)) : fallback;
    }

    public long longValue(String name, long fallback) {
        return values.containsKey(name) ? Long.parseLong(values.get(name)) : fallback;
    }

    /** on/off, true/false, yes/no. */
    public boolean onOff(String name, boolean fallback) {
        if (!values.containsKey(name)) {
            return fallback;
        }
        return switch (values.get(name).toLowerCase()) {
            case "on", "true", "yes" -> true;
            case "off", "false", "no" -> false;
            default -> throw new IllegalArgumentException("--" + name + " must be on or off, got " + values.get(name));
        };
    }
}
