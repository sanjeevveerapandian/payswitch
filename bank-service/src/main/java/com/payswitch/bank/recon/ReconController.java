package com.payswitch.bank.recon;

import com.payswitch.common.bank.ReconPage;
import java.time.Instant;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** GET /recon/entries?from=&to=&page=&size= : the bank's side of reconciliation. Read-only. */
@RestController
public class ReconController {

    private final ReconQueryService recon;

    public ReconController(ReconQueryService recon) {
        this.recon = recon;
    }

    @GetMapping("/recon/entries")
    public ReconPage entries(@RequestParam Instant from, @RequestParam Instant to,
                             @RequestParam(defaultValue = "0") int page,
                             @RequestParam(defaultValue = "500") int size) {
        return recon.entries(from, to, page, size);
    }
}
