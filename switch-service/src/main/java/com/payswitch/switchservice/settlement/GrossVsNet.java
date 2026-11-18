package com.payswitch.switchservice.settlement;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collection;

/**
 * How much netting saved.
 *
 * @param grossAmount    paise that would move if every payment were settled one by one
 * @param netAmountMoved paise that actually move: the sum of the positive positions
 *                       (equal to the sum of the negative ones, because they add up to zero)
 * @param percentSaved   (gross - net) / gross, as a percentage with 2 decimals. Not money, so a
 *                       BigDecimal is fine here; it is never added to a balance.
 */
public record GrossVsNet(long grossAmount, long netAmountMoved, BigDecimal percentSaved) {

    public static GrossVsNet of(Collection<SettlementItem> items, Collection<Long> nets) {
        long gross = items.stream().mapToLong(SettlementItem::amount).reduce(0, Math::addExact);
        long net = nets.stream().mapToLong(Long::longValue).filter(n -> n > 0).reduce(0, Math::addExact);
        BigDecimal saved = gross == 0
                ? BigDecimal.ZERO.setScale(2)
                : BigDecimal.valueOf(gross - net).multiply(BigDecimal.valueOf(100))
                        .divide(BigDecimal.valueOf(gross), 2, RoundingMode.HALF_UP);
        return new GrossVsNet(gross, net, saved);
    }
}
