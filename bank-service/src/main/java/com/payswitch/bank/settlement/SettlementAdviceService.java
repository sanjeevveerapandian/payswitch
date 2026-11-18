package com.payswitch.bank.settlement;

import com.payswitch.bank.service.BankOperationsService;
import com.payswitch.bank.service.PostingService;
import com.payswitch.common.settlement.SettlementAdvice;
import com.payswitch.common.settlement.SettlementAdviceResponse;
import com.payswitch.ledger.EntryLine;
import com.payswitch.ledger.JournalEntry;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Applies a settlement advice: "for cycle 7, your net position is -6000 rupees".
 *
 * <p>During the day every payment left its mark on SWITCH_SETTLEMENT ("who owes whom"). The
 * central bank has now moved real money between the banks' reserves, so this bank moves the same
 * amount from SWITCH_SETTLEMENT to CENTRAL_BANK_RESERVE. One journal entry:
 * <pre>
 *   SWITCH_SETTLEMENT     -net   (Bank A owed 6000: debit it by 6000, the debt is cleared)
 *   CENTRAL_BANK_RESERVE  +net   (and its reserve at the central bank goes down by 6000)
 * </pre>
 *
 * <p>Idempotent by cycleId: claiming the cycle and posting the entry are one database transaction.
 */
@Service
public class SettlementAdviceService {

    public static final String CENTRAL_BANK_RESERVE = "CENTRAL_BANK_RESERVE";
    public static final String OP_SETTLEMENT = "SETTLEMENT";

    private static final Logger log = LoggerFactory.getLogger(SettlementAdviceService.class);

    private final JdbcClient jdbc;
    private final PostingService posting;

    public SettlementAdviceService(JdbcClient jdbc, PostingService posting) {
        this.jdbc = jdbc;
        this.posting = posting;
    }

    /**
     * @throws SettlementAdviceConflictException if this cycle was already applied with a DIFFERENT
     *         amount. That is never a retry; it is a bug somewhere, and a person must look.
     */
    @Transactional
    public SettlementAdviceResponse apply(SettlementAdvice advice) {
        // 1. Claim the cycle. If a copy of this advice is being applied right now, this INSERT
        //    waits for it, then inserts nothing.
        boolean firstTime = jdbc.sql("""
                INSERT INTO settlement_advices (cycle_id, net_amount) VALUES (:cycle, :net)
                ON CONFLICT (cycle_id) DO NOTHING
                """)
                .param("cycle", advice.cycleId())
                .param("net", advice.netAmount())
                .update() == 1;

        if (!firstTime) {
            long applied = appliedAmount(advice.cycleId()).orElseThrow();
            if (applied != advice.netAmount()) {
                throw new SettlementAdviceConflictException(advice.cycleId(), applied, advice.netAmount());
            }
            return new SettlementAdviceResponse(advice.cycleId(), applied);   // a retry: same answer
        }

        // 2. Move the money. A net of 0 needs no entry (a journal line can never be 0).
        if (advice.netAmount() != 0) {
            String id = "CYCLE-" + advice.cycleId();
            posting.post(new JournalEntry("SETTLE-" + advice.cycleId(), id,
                    "Settlement cycle " + advice.cycleId(),
                    List.of(new EntryLine(BankOperationsService.SWITCH_SETTLEMENT, Math.negateExact(advice.netAmount())),
                            new EntryLine(CENTRAL_BANK_RESERVE, advice.netAmount()))),
                    OP_SETTLEMENT);
        }
        log.info("Settlement cycle {} applied: net {} paise", advice.cycleId(), advice.netAmount());
        return new SettlementAdviceResponse(advice.cycleId(), advice.netAmount());
    }

    public Optional<Long> appliedAmount(long cycleId) {
        return jdbc.sql("SELECT net_amount FROM settlement_advices WHERE cycle_id = :cycle")
                .param("cycle", cycleId).query(Long.class).optional();
    }
}
