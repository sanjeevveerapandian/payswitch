package com.payswitch.switchservice.settlement;

import java.util.Collection;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.stereotype.Component;

/**
 * Multilateral netting: each bank's net position = what it RECEIVES minus what it PAYS.
 *
 * <pre>
 *   A -> B 10000,  B -> C 6000,  C -> A 4000   (rupees)
 *   A: receives 4000  - pays 10000 = -6000
 *   B: receives 10000 - pays 6000  = +4000
 *   C: receives 6000  - pays 4000  = +2000      sum = 0, always
 * </pre>
 *
 * <p>Every payment adds +amount to one bank and -amount to another, so the positions always sum
 * to zero. The caller still checks that, because a bug here would move real money.
 *
 * <p>A Spring bean (not a static method) so a test can plant a bug in it.
 */
@Component
public class NetPositionCalculator {

    /**
     * @param banks every bank that takes part; each gets a position, 0 if it had no payments
     * @return bank code -> net position in paise, sorted by bank code
     */
    public Map<String, Long> compute(Collection<SettlementItem> items, Collection<String> banks) {
        Map<String, Long> nets = new TreeMap<>();
        banks.forEach(bank -> nets.put(bank, 0L));
        for (SettlementItem item : items) {
            nets.merge(item.payerBank(), Math.negateExact(item.amount()), Math::addExact);
            nets.merge(item.payeeBank(), item.amount(), Math::addExact);
        }
        return nets;
    }
}
