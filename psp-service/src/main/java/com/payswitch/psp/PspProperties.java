package com.payswitch.psp;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param switchUrl base URL of the switch, e.g. http://localhost:8083
 * @param http      timeouts for calls to the switch
 */
@ConfigurationProperties("psp")
public record PspProperties(URI switchUrl, Http http) {

    public PspProperties {
        if (switchUrl == null) {
            throw new IllegalArgumentException("psp.switch-url must be set");
        }
        if (http == null) {
            throw new IllegalArgumentException("psp.http timeouts must be configured");
        }
    }

    public record Http(Duration connectTimeout, Duration readTimeout) {

        public Http {
            if (connectTimeout == null || connectTimeout.isZero() || connectTimeout.isNegative()
                    || readTimeout == null || readTimeout.isZero() || readTimeout.isNegative()) {
                throw new IllegalArgumentException("psp.http timeouts must be set and positive");
            }
        }
    }
}
