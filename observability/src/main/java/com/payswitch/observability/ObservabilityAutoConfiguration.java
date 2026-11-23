package com.payswitch.observability;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;

/**
 * Switched on in every service just by having this module on the classpath (it is listed in
 * META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports).
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class ObservabilityAutoConfiguration {

    /** First filter of all, so even other filters' log lines carry the txnId. */
    @Bean
    public FilterRegistrationBean<TxnIdFilter> txnIdFilter() {
        FilterRegistrationBean<TxnIdFilter> registration = new FilterRegistrationBean<>(new TxnIdFilter());
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }

    /** Applied by Spring Boot to every RestClient.Builder it hands out. */
    @Bean
    public RestClientCustomizer txnIdForwarding() {
        return builder -> builder.requestInterceptor(new TxnIdForwardingInterceptor());
    }
}
