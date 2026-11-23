package com.payswitch.bank.chaos;

import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * GET /admin/chaos shows the current settings, POST /admin/chaos replaces them.
 * With @Profile("chaos") this controller does not exist at all otherwise, so the URLs are 404.
 */
@RestController
@Profile("chaos")
@RequestMapping("/admin/chaos")
public class ChaosController {

    private final ChaosEngine chaos;

    public ChaosController(ChaosEngine chaos) {
        this.chaos = chaos;
    }

    @GetMapping
    public ChaosSettings get() {
        return chaos.settings();
    }

    @PostMapping
    public ChaosSettings set(@RequestBody ChaosSettings settings) {
        chaos.configure(settings);
        return chaos.settings();
    }

    /** What to do to the next request, once. */
    public record Once(String op, String mode) {
    }

    /** POST /admin/chaos/once {"op":"CREDIT","mode":"error"}: only the NEXT credit fails, then back to normal. */
    @PostMapping("/once")
    public Map<String, ChaosDecision> once(@RequestBody Once request) {
        chaos.armOnce(request.op(), request.mode());
        return chaos.pendingOnce();
    }

    @GetMapping("/once")
    public Map<String, ChaosDecision> pendingOnce() {
        return chaos.pendingOnce();
    }
}
