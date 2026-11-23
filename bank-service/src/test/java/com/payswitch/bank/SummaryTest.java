package com.payswitch.bank;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.payswitch.common.bank.BankSummary;
import com.payswitch.common.bank.CreditRequest;
import com.payswitch.common.bank.DebitRequest;
import com.payswitch.common.bank.ReverseRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;

/** Phase 12: GET /admin/summary follows every kind of money movement. */
class SummaryTest extends BankIntegrationTest {

    @Autowired
    private TestRestTemplate http;

    private BankSummary summary() {
        return http.getForObject("/admin/summary", BankSummary.class);
    }

    @Test
    void customerMoneyAndTheSettlementAccountFollowEachOperation() {
        BankSummary start = summary();
        assertEquals("BANKA", start.bankCode());

        String ravi = newCustomer(500_000);                                   // an opening deposit
        BankSummary opened = summary();
        assertEquals(start.customerMoney() + 500_000, opened.customerMoney());
        assertEquals(start.customerAccounts() + 1, opened.customerAccounts());

        String debit = newTxnId();
        bank.debit(new DebitRequest(debit, ravi, 20_000));                    // Ravi pays out Rs 200
        BankSummary debited = summary();
        assertEquals(opened.customerMoney() - 20_000, debited.customerMoney());
        assertEquals(opened.switchSettlement() + 20_000, debited.switchSettlement(), "held for the switch");
        // Customers + settlement account: unchanged. Money moved INSIDE the bank, nothing was lost.
        assertEquals(opened.customerMoney() + opened.switchSettlement(),
                debited.customerMoney() + debited.switchSettlement());

        bank.reverse(new ReverseRequest(debit));                              // and gets it back
        assertEquals(opened.customerMoney(), summary().customerMoney());
        assertEquals(opened.switchSettlement(), summary().switchSettlement());

        bank.credit(new CreditRequest(newTxnId(), ravi, 5_000));              // someone pays Ravi Rs 50
        BankSummary credited = summary();
        assertEquals(opened.customerMoney() + 5_000, credited.customerMoney());
        assertEquals(opened.switchSettlement() - 5_000, credited.switchSettlement(), "the switch owes us");
    }
}
