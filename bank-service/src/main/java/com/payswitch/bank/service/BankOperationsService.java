package com.payswitch.bank.service;

import com.payswitch.bank.db.AccountRepository;
import com.payswitch.bank.db.AccountRow;
import com.payswitch.bank.db.JournalRepository;
import com.payswitch.bank.db.ProcessedRequestRepository;
import com.payswitch.common.bank.BalanceResponse;
import com.payswitch.common.bank.BankResponse;
import com.payswitch.common.bank.BankResult;
import com.payswitch.common.bank.BankTxnRequest;
import com.payswitch.common.bank.DeclineReason;
import com.payswitch.common.bank.OpType;
import com.payswitch.common.bank.ReverseRequest;
import com.payswitch.common.bank.StatusResponse;
import com.payswitch.common.bank.TxnStatus;
import com.payswitch.ledger.EntryLine;
import com.payswitch.ledger.InsufficientFundsException;
import com.payswitch.ledger.JournalEntry;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * DEBIT, CREDIT and REVERSE, each idempotent by (txnId, op).
 *
 * <p>Every public money method is ONE database transaction that does three things together:
 * claim the (txnId, op) row, move the money, save the response. Either all three commit,
 * or none of them do.
 */
@Service
public class BankOperationsService {

    public static final String SWITCH_SETTLEMENT = "SWITCH_SETTLEMENT";

    private final AccountRepository accounts;
    private final JournalRepository journal;
    private final ProcessedRequestRepository processed;
    private final PostingService posting;

    public BankOperationsService(AccountRepository accounts, JournalRepository journal,
                                 ProcessedRequestRepository processed, PostingService posting) {
        this.accounts = accounts;
        this.journal = journal;
        this.processed = processed;
        this.posting = posting;
    }

    /** Customer -> SWITCH_SETTLEMENT. Ravi's bank takes Rs 200 from Ravi. */
    @Transactional
    public BankResponse debit(BankTxnRequest request) {
        return once(request.txnId(), OpType.DEBIT, () -> {
            Optional<AccountRow> customer = accounts.findByVpa(request.vpa());
            if (customer.isEmpty()) {
                return BankResponse.declined(request.txnId(), OpType.DEBIT, DeclineReason.UNKNOWN_VPA,
                        "No account with VPA " + request.vpa());
            }
            JournalEntry entry = new JournalEntry(newEntryId(), request.txnId(), "UPI debit " + request.vpa(),
                    List.of(EntryLine.debit(customer.get().id(), request.amount()),
                            EntryLine.credit(SWITCH_SETTLEMENT, request.amount())));
            try {
                posting.post(entry, OpType.DEBIT.name());
            } catch (InsufficientFundsException e) {
                return BankResponse.declined(request.txnId(), OpType.DEBIT, DeclineReason.INSUFFICIENT_FUNDS,
                        "Insufficient funds");
            }
            return BankResponse.success(request.txnId(), OpType.DEBIT);
        });
    }

    /** SWITCH_SETTLEMENT -> customer. Anu's bank gives Anu Rs 200. */
    @Transactional
    public BankResponse credit(BankTxnRequest request) {
        return once(request.txnId(), OpType.CREDIT, () -> {
            Optional<AccountRow> customer = accounts.findByVpa(request.vpa());
            if (customer.isEmpty()) {
                return BankResponse.declined(request.txnId(), OpType.CREDIT, DeclineReason.UNKNOWN_VPA,
                        "No account with VPA " + request.vpa());
            }
            JournalEntry entry = new JournalEntry(newEntryId(), request.txnId(), "UPI credit " + request.vpa(),
                    List.of(EntryLine.debit(SWITCH_SETTLEMENT, request.amount()),
                            EntryLine.credit(customer.get().id(), request.amount())));
            posting.post(entry, OpType.CREDIT.name()); // settlement may go negative, so this cannot be declined
            return BankResponse.success(request.txnId(), OpType.CREDIT);
        });
    }

    /**
     * Undoes the DEBIT with this txnId. Example: Ravi was debited Rs 200, but Anu's bank
     * could not credit Anu, so the switch asks Ravi's bank to give the Rs 200 back.
     */
    @Transactional
    public BankResponse reverse(ReverseRequest request) {
        String txnId = request.txnId();
        return once(txnId, OpType.REVERSE, () -> {
            // If the DEBIT never reached us, record it as DECLINED right now. Then a DEBIT that
            // arrives late (slow network) is refused instead of taking money nobody will return.
            // If the DEBIT is being processed at this very moment, this INSERT waits for it.
            BankResponse blockedDebit = BankResponse.declined(txnId, OpType.DEBIT,
                    DeclineReason.ALREADY_REVERSED, "Debit arrived after its reversal");
            if (processed.insertIfAbsent(blockedDebit)) {
                return BankResponse.declined(txnId, OpType.REVERSE, DeclineReason.ORIGINAL_NOT_FOUND,
                        "No debit with txnId " + txnId);
            }

            BankResponse original = processed.find(txnId, OpType.DEBIT).orElseThrow();
            if (original.result() != BankResult.SUCCESS) {
                return BankResponse.declined(txnId, OpType.REVERSE, DeclineReason.ORIGINAL_NOT_SUCCESSFUL,
                        "Debit " + txnId + " did not succeed, nothing to reverse");
            }

            // Same lines as the debit with signs flipped: what was debited is now credited.
            List<EntryLine> undo = journal.findLines(txnId, OpType.DEBIT.name()).stream()
                    .map(line -> new EntryLine(line.accountId(), Math.negateExact(line.amount())))
                    .toList();
            posting.post(new JournalEntry(newEntryId(), txnId, "Reversal of debit " + txnId, undo),
                    OpType.REVERSE.name());
            return BankResponse.success(txnId, OpType.REVERSE);
        });
    }

    @Transactional(readOnly = true)
    public StatusResponse status(String txnId, OpType op) {
        TxnStatus status = processed.find(txnId, op)
                .map(saved -> saved.result() == BankResult.SUCCESS ? TxnStatus.SUCCESS : TxnStatus.FAILED)
                .orElse(TxnStatus.NOT_FOUND);
        return new StatusResponse(txnId, op, status);
    }

    @Transactional(readOnly = true)
    public BalanceResponse balance(String vpa) {
        AccountRow row = accounts.findByVpa(vpa).orElseThrow(() -> new UnknownVpaException(vpa));
        return new BalanceResponse(vpa, row.naturalBalance());
    }

    /**
     * The idempotency wrapper. First caller for (txnId, op) runs {@code work} and saves its answer.
     * Every later caller gets the saved answer and {@code work} never runs again.
     */
    private BankResponse once(String txnId, OpType op, Supplier<BankResponse> work) {
        if (!processed.claim(txnId, op)) {
            return processed.find(txnId, op).orElseThrow(() ->
                    new IllegalStateException("Claimed row has no saved response: " + txnId + "/" + op));
        }
        BankResponse response = work.get();
        processed.complete(response);
        return response;
    }

    private static String newEntryId() {
        return UUID.randomUUID().toString();
    }
}
