package com.payswitch.switchservice.settlement;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** Multilateral netting on its own: no Spring, no database. Amounts in paise (Rs 1 = 100). */
class NetPositionCalculatorTest {

    private static final List<String> BANKS = List.of("BANKA", "BANKB", "BANKC");
    private static final long RUPEE = 100;

    private final NetPositionCalculator calculator = new NetPositionCalculator();

    private static SettlementItem item(String id, String payer, String payee, long rupees) {
        return new SettlementItem(id, payer, payee, rupees * RUPEE);
    }

    /** A -> B 10000, B -> C 6000, C -> A 4000 (rupees). */
    private static final List<SettlementItem> THREE_BANK_EXAMPLE = List.of(
            item("T1", "BANKA", "BANKB", 10_000),
            item("T2", "BANKB", "BANKC", 6_000),
            item("T3", "BANKC", "BANKA", 4_000));

    @Test
    void theThreeBankExampleByHand() {
        Map<String, Long> nets = calculator.compute(THREE_BANK_EXAMPLE, BANKS);

        assertEquals(Map.of("BANKA", -6_000 * RUPEE, "BANKB", 4_000 * RUPEE, "BANKC", 2_000 * RUPEE), nets);
        assertEquals(0, nets.values().stream().mapToLong(Long::longValue).sum());
    }

    @Test
    void grossVsNetForTheThreeBankExample() {
        Map<String, Long> nets = calculator.compute(THREE_BANK_EXAMPLE, BANKS);

        GrossVsNet g = GrossVsNet.of(THREE_BANK_EXAMPLE, nets.values());

        assertEquals(20_000 * RUPEE, g.grossAmount());           // 10000 + 6000 + 4000
        assertEquals(6_000 * RUPEE, g.netAmountMoved());          // only Bank A pays, Rs 6000
        assertEquals(new BigDecimal("70.00"), g.percentSaved());
    }

    @Test
    void aRandomThousandPaymentCycleSumsToZeroAndMatchesAPlainRecount() {
        Random random = new Random(42);
        List<SettlementItem> items = new ArrayList<>();
        Map<String, Long> expected = new HashMap<>(Map.of("BANKA", 0L, "BANKB", 0L, "BANKC", 0L));
        for (int i = 0; i < 1_000; i++) {
            String payer = BANKS.get(random.nextInt(3));
            String payee = BANKS.get(random.nextInt(3));
            long amount = 1 + random.nextInt(10_000_000);
            items.add(new SettlementItem("T" + i, payer, payee, amount));
            expected.merge(payer, -amount, Long::sum);
            expected.merge(payee, amount, Long::sum);
        }

        Map<String, Long> nets = calculator.compute(items, BANKS);

        assertEquals(0, nets.values().stream().mapToLong(Long::longValue).sum());
        assertEquals(expected, nets);
    }

    @Test
    void aBankWithNoPaymentsStillGetsAZeroPosition() {
        Map<String, Long> nets = calculator.compute(List.of(item("T1", "BANKA", "BANKB", 200)), BANKS);

        assertEquals(0L, nets.get("BANKC"));
    }

    @Test
    void aPaymentInsideOneBankNetsToZeroForThatBank() {
        Map<String, Long> nets = calculator.compute(List.of(item("T1", "BANKA", "BANKA", 200)), BANKS);

        assertEquals(Map.of("BANKA", 0L, "BANKB", 0L, "BANKC", 0L), nets);
    }

    @Test
    void anEmptyCycleSavesNothingAndDoesNotDivideByZero() {
        GrossVsNet g = GrossVsNet.of(List.of(), List.of(0L, 0L, 0L));

        assertEquals(new GrossVsNet(0, 0, new BigDecimal("0.00")), g);
    }
}
