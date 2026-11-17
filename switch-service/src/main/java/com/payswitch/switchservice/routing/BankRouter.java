package com.payswitch.switchservice.routing;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** Answers "which bank owns this VPA?" by looking up the handle after the @. */
@Component
public class BankRouter {

    private final SwitchProperties properties;

    public BankRouter(SwitchProperties properties) {
        this.properties = properties;
    }

    /** Empty if no bank is registered for the handle (e.g. ravi@nobank). */
    public Optional<BankRoute> route(Vpa vpa) {
        return Optional.ofNullable(properties.banks().get(vpa.handle()));
    }

    /** Every bank the switch talks to, once each. */
    public Collection<BankRoute> allBanks() {
        return List.copyOf(properties.banks().values().stream().distinct().toList());
    }
}
