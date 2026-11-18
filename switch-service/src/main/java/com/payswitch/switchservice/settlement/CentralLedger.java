package com.payswitch.switchservice.settlement;

import com.payswitch.ledger.Account;
import com.payswitch.ledger.AccountType;
import com.payswitch.ledger.EntryLine;
import com.payswitch.ledger.JournalEntry;
import com.payswitch.ledger.Ledger;
import com.payswitch.ledger.LedgerInvariants;
import com.payswitch.ledger.Postings;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The central bank's books, kept with ledger-core's rules (the same code the banks use).
 *
 * <p>Account design, from the CENTRAL BANK's point of view: one account per bank,
 * RESERVE-BANKA, RESERVE-BANKB, ... Each is a LIABILITY: it is money the central bank holds FOR
 * that bank, so it "owes" it back. Its natural balance is what the bank has at the central bank.
 *
 * <p>Settling a cycle is ONE journal entry with one line per bank that has a non-zero position:
 * <pre>
 *   RESERVE-BANKA   debit  6000   (A's reserve goes down: it paid out more than it took in)
 *   RESERVE-BANKB   credit 4000
 *   RESERVE-BANKC   credit 2000   lines sum to 0
 * </pre>
 * No extra "balancing" account is needed: multilateral net positions always sum to zero, so the
 * entry balances on its own. If they ever did not, {@link JournalEntry}'s constructor refuses it:
 * a second, independent check after the one in {@link SettlementService}.
 *
 * <p>Reserves start at 0 and may go negative (an overdraft at the central bank), mirroring each
 * bank's CENTRAL_BANK_RESERVE account.
 */
@Service
public class CentralLedger {

    private final JdbcClient jdbc;

    public CentralLedger(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public static String accountId(String bankCode) {
        return "RESERVE-" + bankCode;
    }

    public boolean isPosted(long cycleId) {
        return jdbc.sql("SELECT COUNT(*) FROM central_journal_entries WHERE cycle_id = :c")
                .param("c", cycleId).query(Integer.class).single() > 0;
    }

    /**
     * Posts the cycle's single settlement entry. Must run inside the caller's transaction, which
     * holds the cycle's row lock, so two settlers cannot both post.
     *
     * @param nets bank code -> net position (receives minus pays), in paise
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void postSettlement(long cycleId, Map<String, Long> nets) {
        List<EntryLine> lines = new ArrayList<>();
        nets.forEach((bank, net) -> {
            if (net != 0) {
                // Receives (+) = the central bank owes it more = CREDIT its liability account.
                lines.add(new EntryLine(accountId(bank), Math.negateExact(net)));
            }
        });
        if (lines.isEmpty()) {
            return;                       // nothing moves (no payments, or everyone exactly even)
        }
        JournalEntry entry = new JournalEntry("CB-SETTLE-" + cycleId, "CYCLE-" + cycleId,
                "Net settlement of cycle " + cycleId, lines);

        // Lock the touched accounts in sorted order (no deadlocks), let ledger-core decide, then write.
        Map<String, Account> accounts = new HashMap<>();
        Map<String, Long> raw = new HashMap<>();
        for (String id : lines.stream().map(EntryLine::accountId).sorted().toList()) {
            openIfMissing(id);
            jdbc.sql("SELECT id, name, type, allow_negative, raw_balance FROM central_accounts WHERE id = :id FOR UPDATE")
                    .param("id", id)
                    .query(rs -> {
                        accounts.put(id, new Account(id, rs.getString("name"),
                                AccountType.valueOf(rs.getString("type")), rs.getBoolean("allow_negative")));
                        raw.put(id, rs.getLong("raw_balance"));
                    });
        }
        Map<String, Long> newRaw = Postings.newBalances(accounts, raw, entry);

        jdbc.sql("INSERT INTO central_journal_entries (id, cycle_id, description) VALUES (:id, :cycle, :d)")
                .param("id", entry.id()).param("cycle", cycleId).param("d", entry.description()).update();
        for (EntryLine line : entry.lines()) {
            jdbc.sql("INSERT INTO central_entry_lines (journal_entry_id, account_id, amount) VALUES (:e, :a, :amt)")
                    .param("e", entry.id()).param("a", line.accountId()).param("amt", line.amount()).update();
        }
        newRaw.forEach((id, balance) -> jdbc.sql("UPDATE central_accounts SET raw_balance = :b WHERE id = :id")
                .param("b", balance).param("id", id).update());
    }

    /** Bank code -> what that bank holds at the central bank, in paise. */
    public Map<String, Long> reserves() {
        Map<String, Long> reserves = new TreeMap<>();
        jdbc.sql("SELECT id, type, raw_balance FROM central_accounts ORDER BY id")
                .query(rs -> {
                    reserves.put(rs.getString("id").substring("RESERVE-".length()),
                            AccountType.valueOf(rs.getString("type")).naturalBalance(rs.getLong("raw_balance")));
                });
        return reserves;
    }

    /**
     * Replays the whole central journal into ledger-core's in-memory {@link Ledger} and runs
     * {@link LedgerInvariants}: books sum to zero, no forbidden negatives, journal = balances.
     *
     * @throws IllegalStateException if any rule is broken
     */
    @Transactional(readOnly = true)
    public void audit() {
        Ledger ledger = new Ledger();
        Map<String, Long> stored = new LinkedHashMap<>();
        jdbc.sql("SELECT id, name, type, allow_negative, raw_balance FROM central_accounts ORDER BY id")
                .query(rs -> {
                    ledger.openAccount(new Account(rs.getString("id"), rs.getString("name"),
                            AccountType.valueOf(rs.getString("type")), rs.getBoolean("allow_negative")));
                    stored.put(rs.getString("id"), rs.getLong("raw_balance"));
                });
        Map<String, List<EntryLine>> lines = new LinkedHashMap<>();
        Map<String, String> txnIds = new HashMap<>();
        jdbc.sql("""
                SELECT e.id, e.cycle_id, l.account_id, l.amount FROM central_journal_entries e
                JOIN central_entry_lines l ON l.journal_entry_id = e.id
                ORDER BY e.created_at, e.id, l.id
                """)
                .query(rs -> {
                    lines.computeIfAbsent(rs.getString("id"), k -> new ArrayList<>())
                            .add(new EntryLine(rs.getString("account_id"), rs.getLong("amount")));
                    txnIds.put(rs.getString("id"), "CYCLE-" + rs.getLong("cycle_id"));
                });
        // new JournalEntry(...) refuses an unbalanced entry, so a corrupted one fails right here.
        lines.forEach((id, entryLines) -> ledger.post(new JournalEntry(id, txnIds.get(id), "replay", entryLines)));

        LedgerInvariants.check(ledger);
        stored.forEach((id, raw) -> {
            if (ledger.rawBalance(id) != raw) {
                throw new IllegalStateException("Central account " + id + " is " + raw
                        + " but the journal says " + ledger.rawBalance(id));
            }
        });
    }

    private void openIfMissing(String id) {
        jdbc.sql("""
                INSERT INTO central_accounts (id, name, type, allow_negative) VALUES (:id, :name, 'LIABILITY', TRUE)
                ON CONFLICT (id) DO NOTHING
                """)
                .param("id", id).param("name", "Reserve of " + id.substring("RESERVE-".length()))
                .update();
    }
}
