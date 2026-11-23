package com.payswitch.switchservice.money;

import com.payswitch.common.bank.BankSummary;
import com.payswitch.switchservice.db.TransactionRepository;
import com.payswitch.switchservice.routing.BankRoute;
import com.payswitch.switchservice.routing.BankRouter;
import com.payswitch.switchservice.routing.SwitchProperties;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** GET /admin/money-summary: every bank's money, added up, plus payments per state. */
@RestController
public class MoneySummaryController {

    private final BankRouter router;
    private final TransactionRepository txns;
    private final RestClient http;

    public MoneySummaryController(BankRouter router, TransactionRepository txns, RestClient.Builder builder,
                                  SwitchProperties properties) {
        this.router = router;
        this.txns = txns;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.http().connectTimeout());
        factory.setReadTimeout(properties.http().readTimeout());
        this.http = builder.requestFactory(factory).build();
    }

    @GetMapping("/admin/money-summary")
    public MoneySummary summary() {
        List<BankSummary> banks = new ArrayList<>();
        for (BankRoute bank : router.allBanks().stream().sorted(java.util.Comparator.comparing(BankRoute::code)).toList()) {
            try {
                banks.add(http.get().uri(bank.url() + "/admin/summary").retrieve().body(BankSummary.class));
            } catch (RestClientException e) {
                // A partial total would look like money vanished. Better to say "unknown".
                throw new BankUnreachableException(bank.code(), e);
            }
        }
        Map<String, Long> byState = new TreeMap<>();
        txns.countByState().forEach((state, n) -> byState.put(state.name(), n));
        return MoneySummary.of(banks, byState);
    }

    @ExceptionHandler(BankUnreachableException.class)
    public ProblemDetail unreachable(BankUnreachableException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
    }

    static class BankUnreachableException extends RuntimeException {
        BankUnreachableException(String bank, Throwable cause) {
            super(bank + " did not answer; the total cannot be computed right now", cause);
        }
    }
}
