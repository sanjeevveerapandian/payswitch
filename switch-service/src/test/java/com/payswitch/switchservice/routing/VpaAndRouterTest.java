package com.payswitch.switchservice.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class VpaAndRouterTest {

    private final BankRouter router = new BankRouter(new SwitchProperties(
            new SwitchProperties.Http(Duration.ofSeconds(2), Duration.ofSeconds(5)), Map.of(
            "banka", new BankRoute("BANKA", URI.create("http://localhost:8081")),
            "bankb", new BankRoute("BANKB", URI.create("http://localhost:8082")))));

    @Test
    void parsesUserAndHandle() {
        Vpa vpa = Vpa.parse("ravi@banka");

        assertEquals("ravi", vpa.user());
        assertEquals("banka", vpa.handle());
        assertEquals("ravi@banka", vpa.address());
    }

    @Test
    void vpaIsNotCaseSensitive() {
        assertEquals(Vpa.parse("ravi@banka"), Vpa.parse("  Ravi@BankA "));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "ravi", "@banka", "ravi@", "ravi@@banka", "ravi@bank a", "ra vi@banka",
            "ravi@banka@bankb", "ravi@b", "ravi@bank1", "ravi#1@banka"})
    void malformedVpaIsRejected(String text) {
        assertThrows(IllegalArgumentException.class, () -> Vpa.parse(text));
    }

    @Test
    void routesEachHandleToItsBank() {
        BankRoute a = router.route(Vpa.parse("ravi@banka")).orElseThrow();
        BankRoute b = router.route(Vpa.parse("anu@bankb")).orElseThrow();

        assertEquals("BANKA", a.code());
        assertEquals(URI.create("http://localhost:8081"), a.url());
        assertEquals("BANKB", b.code());
        assertEquals(URI.create("http://localhost:8082"), b.url());
    }

    @Test
    void unknownHandleHasNoRoute() {
        assertTrue(router.route(Vpa.parse("ravi@nobank")).isEmpty());
    }
}
