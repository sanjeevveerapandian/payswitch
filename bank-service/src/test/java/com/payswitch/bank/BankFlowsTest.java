package com.payswitch.bank;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.payswitch.common.bank.BankResponse;
import com.payswitch.common.bank.BankResult;
import com.payswitch.common.bank.CreditRequest;
import com.payswitch.common.bank.DebitRequest;
import com.payswitch.common.bank.DeclineReason;
import com.payswitch.common.bank.ReverseRequest;
import org.junit.jupiter.api.Test;

/** The happy paths and business declines. @AfterEach in the base class runs the ledger audit. */
class BankFlowsTest extends BankIntegrationTest {

    @Test
    void debitMovesMoneyFromCustomerToSettlement() {
        String ravi = newCustomer(500_000);           // Rs 5,000
        long settlementBefore = settlement();

        BankResponse r = bank.debit(new DebitRequest(newTxnId(), ravi, 20_000)); // Rs 200

        assertEquals(BankResult.SUCCESS, r.result());
        assertNull(r.reason());
        assertEquals(480_000, balance(ravi));
        assertEquals(settlementBefore + 20_000, settlement()); // bank now owes the switch Rs 200 more
    }

    @Test
    void creditMovesMoneyFromSettlementToCustomer() {
        String anu = newCustomer(0);
        long settlementBefore = settlement();

        BankResponse r = bank.credit(new CreditRequest(newTxnId(), anu, 20_000));

        assertEquals(BankResult.SUCCESS, r.result());
        assertEquals(20_000, balance(anu));
        assertEquals(settlementBefore - 20_000, settlement());
    }

    @Test
    void reverseGivesTheDebitedMoneyBack() {
        String ravi = newCustomer(500_000);
        long settlementBefore = settlement();
        String txn = newTxnId();
        bank.debit(new DebitRequest(txn, ravi, 20_000));

        BankResponse r = bank.reverse(new ReverseRequest(txn));

        assertEquals(BankResult.SUCCESS, r.result());
        assertEquals(500_000, balance(ravi));
        assertEquals(settlementBefore, settlement());
        assertEquals(2, journalEntriesFor(txn)); // the debit stays in history; the reversal is a new entry
    }

    @Test
    void insufficientFundsIsDeclinedAndNothingMoves() {
        String ravi = newCustomer(10_000);            // Rs 100
        long settlementBefore = settlement();
        String txn = newTxnId();

        BankResponse r = bank.debit(new DebitRequest(txn, ravi, 20_000));

        assertEquals(BankResult.DECLINED, r.result());
        assertEquals(DeclineReason.INSUFFICIENT_FUNDS, r.reason());
        assertEquals(10_000, balance(ravi));
        assertEquals(settlementBefore, settlement());
        assertEquals(0, journalEntriesFor(txn));
    }

    @Test
    void unknownVpaIsDeclined() {
        BankResponse debit = bank.debit(new DebitRequest(newTxnId(), "nobody@banka", 20_000));
        BankResponse credit = bank.credit(new CreditRequest(newTxnId(), "nobody@banka", 20_000));

        assertEquals(DeclineReason.UNKNOWN_VPA, debit.reason());
        assertEquals(DeclineReason.UNKNOWN_VPA, credit.reason());
    }

    @Test
    void reversingADeclinedDebitIsDeclined() {
        String ravi = newCustomer(10_000);
        String txn = newTxnId();
        bank.debit(new DebitRequest(txn, ravi, 20_000)); // declined: not enough money

        BankResponse r = bank.reverse(new ReverseRequest(txn));

        assertEquals(DeclineReason.ORIGINAL_NOT_SUCCESSFUL, r.reason());
        assertEquals(10_000, balance(ravi));
    }

    @Test
    void ravisSeededBalanceIsLoadedForBankA() {
        assertEquals(500_000, balance("ravi@banka"));
        assertEquals(200_000, balance("meena@banka"));
    }
}
