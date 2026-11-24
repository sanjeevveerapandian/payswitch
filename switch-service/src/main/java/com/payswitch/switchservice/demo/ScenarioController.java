package com.payswitch.switchservice.demo;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * POST /admin/scenario/{name}: set up one failure and make one payment (demo only).
 * POST /admin/demo/reseed: put the demo customers back to their starting balances (admin only at the gateway).
 */
@RestController
@ConditionalOnProperty(name = "switch.demo.enabled", havingValue = "true")
public class ScenarioController {

    private final ScenarioService scenarios;
    private final DemoReseedService reseed;

    public ScenarioController(ScenarioService scenarios, DemoReseedService reseed) {
        this.scenarios = scenarios;
        this.reseed = reseed;
    }

    /** 200 with the outcome; 202 for switch-crash, whose payment only starts after this answer. */
    @PostMapping("/admin/scenario/{name}")
    public ResponseEntity<ScenarioService.Result> run(@PathVariable String name) {
        ScenarioService.Result result = scenarios.run(name);
        return ResponseEntity.status("switch-crash".equals(name) ? HttpStatus.ACCEPTED : HttpStatus.OK).body(result);
    }

    @PostMapping("/admin/demo/reseed")
    public DemoReseedService.Result reseed() {
        return reseed.reseed();
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail unknown(IllegalArgumentException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler({ScenarioService.ScenarioUnavailableException.class, IllegalStateException.class})
    public ProblemDetail unavailable(RuntimeException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    }

    /** 429 Too Many Requests + Retry-After: "busy, try again in a few seconds". */
    @ExceptionHandler(DemoLock.DemoBusyException.class)
    public ResponseEntity<ProblemDetail> busy(DemoLock.DemoBusyException e) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).header(HttpHeaders.RETRY_AFTER, "5")
                .body(ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS, e.getMessage()));
    }
}
