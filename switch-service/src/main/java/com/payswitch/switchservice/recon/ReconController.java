package com.payswitch.switchservice.recon;

import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** POST /admin/recon/run?from=&to= and GET /admin/recon/runs/{id}. Times are ISO-8601 instants. */
@RestController
public class ReconController {

    private final ReconciliationService recon;

    public ReconController(ReconciliationService recon) {
        this.recon = recon;
    }

    @PostMapping("/admin/recon/run")
    public ReconReport run(@RequestParam Instant from, @RequestParam Instant to) {
        return recon.run(from, to);
    }

    @GetMapping("/admin/recon/runs/{id}")
    public ReconReport report(@PathVariable long id) {
        return recon.report(id);
    }

    @ExceptionHandler(ReconRunNotFoundException.class)
    public ProblemDetail notFound(ReconRunNotFoundException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    }

    /** A bank could not be read: 503, and the body says nothing was decided. */
    @ExceptionHandler(ReconUnavailableException.class)
    public ProblemDetail bankUnavailable(ReconUnavailableException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,
                e.getMessage() + ". Nothing was decided; run this window again later.");
    }
}
