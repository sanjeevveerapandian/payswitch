package com.payswitch.psp;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * POST /admin/load?count=1000 starts a background run (202), or 409 if one is already running.
 * GET /admin/load shows its live counters.
 */
@RestController
public class LoadController {

    private final LoadRunner runner;

    public LoadController(LoadRunner runner) {
        this.runner = runner;
    }

    @PostMapping("/admin/load")
    public ResponseEntity<LoadRunner.Status> start(@RequestParam(defaultValue = "1000") int count) {
        boolean started = runner.start(count);
        return ResponseEntity.status(started ? HttpStatus.ACCEPTED : HttpStatus.CONFLICT).body(runner.status());
    }

    @GetMapping("/admin/load")
    public LoadRunner.Status status() {
        return runner.status();
    }
}
