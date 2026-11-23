package com.payswitch.switchservice.demo;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** POST /admin/scenario/{name}: set up one failure and make one payment (demo only). */
@RestController
@ConditionalOnProperty(name = "switch.demo.enabled", havingValue = "true")
public class ScenarioController {

    private final ScenarioService scenarios;

    public ScenarioController(ScenarioService scenarios) {
        this.scenarios = scenarios;
    }

    /** 200 with the outcome; 202 for switch-crash, whose payment only starts after this answer. */
    @PostMapping("/admin/scenario/{name}")
    public ResponseEntity<ScenarioService.Result> run(@PathVariable String name) {
        ScenarioService.Result result = scenarios.run(name);
        return ResponseEntity.status("switch-crash".equals(name) ? HttpStatus.ACCEPTED : HttpStatus.OK).body(result);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail unknown(IllegalArgumentException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler({ScenarioService.ScenarioUnavailableException.class, IllegalStateException.class})
    public ProblemDetail unavailable(RuntimeException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    }
}
