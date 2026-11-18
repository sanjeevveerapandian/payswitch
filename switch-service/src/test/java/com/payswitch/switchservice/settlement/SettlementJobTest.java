package com.payswitch.switchservice.settlement;

import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class SettlementJobTest {

    private final SettlementService service = mock(SettlementService.class);

    @Test
    void whenDisabledItDoesNothing() {
        new SettlementJob(service, new SettlementProperties(false, Duration.ofHours(1))).onSchedule();

        verifyNoInteractions(service);
    }

    @Test
    void whenEnabledItRetriesUnfinishedCyclesThenClosesTheOpenOne() {
        when(service.closeAndSettle(null)).thenReturn(new SettlementReport(1, "SETTLED", null, null, null, null,
                java.util.List.of(), java.util.List.of(), 0, GrossVsNet.of(java.util.List.of(), java.util.List.of())));

        new SettlementJob(service, new SettlementProperties(true, Duration.ofHours(1))).onSchedule();

        InOrder order = inOrder(service);
        order.verify(service).settleUnfinished();
        order.verify(service).closeAndSettle(isNull());
    }

    @Test
    void aFailureIsLoggedAndDoesNotKillTheScheduler() {
        when(service.closeAndSettle(null)).thenThrow(new IllegalStateException("database down"));

        new SettlementJob(service, new SettlementProperties(true, Duration.ofHours(1))).onSchedule();
    }
}
