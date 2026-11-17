package com.payswitch.switchservice.recon;

import com.payswitch.common.bank.ReconEntry;
import com.payswitch.common.bank.ReconPage;
import com.payswitch.switchservice.routing.BankRoute;
import com.payswitch.switchservice.routing.SwitchProperties;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Reads a bank's /recon/entries for a window, page by page.
 *
 * <p>All or nothing: if any page cannot be read, this throws. Reconciliation must never decide
 * "the bank has no record" from a list it only partly received.
 */
@Component
public class BankReconClient {

    private static final int MAX_PAGES = 100_000;

    private final RestClient http;

    public BankReconClient(RestClient.Builder builder, SwitchProperties properties) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.http().connectTimeout());
        factory.setReadTimeout(properties.http().readTimeout());
        this.http = builder.requestFactory(factory).build();
    }

    /** @throws ReconUnavailableException if the bank could not be read completely */
    public List<ReconEntry> fetchAll(BankRoute bank, Instant from, Instant to, int pageSize) {
        List<ReconEntry> all = new ArrayList<>();
        try {
            for (int page = 0; page < MAX_PAGES; page++) {
                ReconPage result = http.get()
                        .uri(bank.url() + "/recon/entries?from={from}&to={to}&page={page}&size={size}",
                                from, to, page, pageSize)
                        .retrieve()
                        .body(ReconPage.class);
                if (result == null || result.entries() == null) {
                    throw new ReconUnavailableException(bank.code() + " returned an empty recon page");
                }
                all.addAll(result.entries());
                if (!result.hasMore()) {
                    return all;
                }
            }
        } catch (RestClientException e) {
            throw new ReconUnavailableException("Could not read " + bank.code() + " recon entries: " + e.getMessage());
        }
        throw new ReconUnavailableException(bank.code() + " has more than " + MAX_PAGES + " pages");
    }
}
