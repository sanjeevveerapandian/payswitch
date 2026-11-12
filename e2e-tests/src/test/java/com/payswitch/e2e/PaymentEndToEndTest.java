package com.payswitch.e2e;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.payswitch.common.bank.BalanceResponse;
import com.payswitch.common.bank.StatusResponse;
import com.payswitch.common.bank.TxnStatus;
import com.payswitch.common.pay.RespPay;
import com.payswitch.common.pay.TxnDetails;
import com.payswitch.common.pay.TxnEventView;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

/**
 * Real payments through all four services over real HTTP. Seed data: Ravi Rs 5,000 and
 * Meena Rs 2,000 at Bank A; Anu's shop Rs 1,000 and Kiran Rs 3,000 at Bank B.
 */
class PaymentEndToEndTest {

    private static final PaymentSystem system = new PaymentSystem();
    private static final RestClient http = RestClient.create();

    private Money before;

    @BeforeAll
    static void startEverything() throws Exception {
        system.start();
    }

    @AfterAll
    static void stopEverything() {
        system.close();
    }

    @BeforeEach
    void rememberWhereTheMoneyIs() {
        before = Money.snapshot();
    }

    /** After EVERY test, whatever happened: no money was created or destroyed. */
    @AfterEach
    void moneyIsConserved() {
        Money after = Money.snapshot();
        assertEquals(before.customersA + before.customersB, after.customersA + after.customersB,
                "total customer money across both banks changed");
        // What Bank A owes the switch + what Bank B owes the switch is always zero in total:
        // every rupee one bank is waiting to send, the other bank has already paid out.
        assertEquals(before.settlementA + before.settlementB, after.settlementA + after.settlementB,
                "money is stuck in a settlement account");
        system.auditBothBanks();
    }

    @Test
    void raviPaysAnu200() {
        long ravi = balance(system.bankAUrl(), "ravi@banka");
        long anu = balance(system.bankBUrl(), "anu@bankb");

        RespPay resp = pay("ravi@banka", "anu@bankb", "200.00");

        assertEquals("SUCCESS", resp.state());
        assertEquals(-20_000, balance(system.bankAUrl(), "ravi@banka") - ravi);
        assertEquals(+20_000, balance(system.bankBUrl(), "anu@bankb") - anu);

        // Moved exactly once: Bank A's customers hold Rs 200 less, Bank B's hold Rs 200 more.
        Money after = Money.snapshot();
        assertEquals(-20_000, after.customersA - before.customersA);
        assertEquals(+20_000, after.customersB - before.customersB);
        // Bank A now owes the switch Rs 200; the switch owes Bank B Rs 200.
        assertEquals(+20_000, after.settlementA - before.settlementA);
        assertEquals(-20_000, after.settlementB - before.settlementB);

        assertEquals(List.of("INITIATED", "DEBIT_SENT", "DEBITED", "CREDIT_SENT", "SUCCESS"), path(resp.txnId()));
        assertEquals(TxnStatus.SUCCESS, bankStatus(system.bankAUrl(), resp.txnId(), "DEBIT"));
        assertEquals(TxnStatus.SUCCESS, bankStatus(system.bankBUrl(), resp.txnId(), "CREDIT"));
        assertEquals(TxnStatus.NOT_FOUND, bankStatus(system.bankAUrl(), resp.txnId(), "REVERSE"));

        // The PSP's status lookup agrees with the switch.
        RespPay status = http.get().uri(system.pspUrl() + "/pay/" + resp.txnId()).retrieve().body(RespPay.class);
        assertEquals(resp, status);
    }

    @Test
    void insufficientFundsFailsAndNoMoneyMovesAnywhere() {
        // Meena has Rs 2,000 and tries to pay Rs 99,999.
        RespPay resp = pay("meena@banka", "anu@bankb", "99999");

        assertEquals("FAILED", resp.state());
        assertEquals("DEBIT_DECLINED:INSUFFICIENT_FUNDS", resp.reason());
        assertEquals(before, Money.snapshot());                     // nothing moved at either bank
        assertEquals(List.of("INITIATED", "DEBIT_SENT", "FAILED"), path(resp.txnId()));
        assertEquals(TxnStatus.FAILED, bankStatus(system.bankAUrl(), resp.txnId(), "DEBIT"));
        assertEquals(TxnStatus.NOT_FOUND, bankStatus(system.bankBUrl(), resp.txnId(), "CREDIT"));
    }

    @Test
    void unknownPayeeAtBankBIsReversedAndRaviGetsEverythingBack() {
        long ravi = balance(system.bankAUrl(), "ravi@banka");

        // The handle "bankb" is fine, so the switch routes it. Bank B has no customer "ghost".
        RespPay resp = pay("ravi@banka", "ghost@bankb", "200.00");

        assertEquals("REVERSED", resp.state());
        assertEquals("REVERSAL_OK", resp.reason());
        assertEquals(ravi, balance(system.bankAUrl(), "ravi@banka"));
        assertEquals(before, Money.snapshot());                     // debit + reversal cancel out

        assertEquals(List.of("INITIATED", "DEBIT_SENT", "DEBITED", "CREDIT_SENT", "REVERSAL_SENT", "REVERSED"),
                path(resp.txnId()));
        assertEquals("CREDIT_DECLINED:UNKNOWN_VPA", events(resp.txnId()).get(4).reason());
        // Bank A really did both: took the money, then gave it back.
        assertEquals(TxnStatus.SUCCESS, bankStatus(system.bankAUrl(), resp.txnId(), "DEBIT"));
        assertEquals(TxnStatus.SUCCESS, bankStatus(system.bankAUrl(), resp.txnId(), "REVERSE"));
        assertEquals(TxnStatus.FAILED, bankStatus(system.bankBUrl(), resp.txnId(), "CREDIT"));
    }

    @Test
    void unknownBankHandleFailsBeforeAnyBankIsCalled() {
        RespPay resp = pay("ravi@banka", "anu@nobank", "200.00");

        assertEquals("FAILED", resp.state());
        assertEquals("UNKNOWN_HANDLE", resp.reason());
        assertEquals(before, Money.snapshot());
        assertEquals(List.of("INITIATED", "FAILED"), path(resp.txnId()));
        assertEquals(TxnStatus.NOT_FOUND, bankStatus(system.bankAUrl(), resp.txnId(), "DEBIT"));
    }

    @Test
    void paymentInTheOtherDirectionWorksToo() {
        RespPay resp = pay("kiran@bankb", "meena@banka", "150.50");

        assertEquals("SUCCESS", resp.state());
        Money after = Money.snapshot();
        assertEquals(+15_050, after.customersA - before.customersA);
        assertEquals(-15_050, after.customersB - before.customersB);
    }

    @Test
    void badAmountIsRejectedAtThePspAndNothingMoves() {
        HttpClientErrorException e = assertThrows(HttpClientErrorException.class,
                () -> pay("ravi@banka", "anu@bankb", "200.505"));

        assertEquals(400, e.getStatusCode().value());
        assertEquals(before, Money.snapshot());
    }

    // ---------------------------------------------------------------------------------------

    private static RespPay pay(String payerVpa, String payeeVpa, String rupees) {
        return http.post().uri(system.pspUrl() + "/pay")
                .body(Map.of("payerVpa", payerVpa, "payeeVpa", payeeVpa, "amount", rupees))
                .retrieve()
                .body(RespPay.class);
    }

    private static long balance(String bankUrl, String vpa) {
        return http.get().uri(bankUrl + "/accounts/" + vpa + "/balance")
                .retrieve().body(BalanceResponse.class).balance();
    }

    private static TxnStatus bankStatus(String bankUrl, String txnId, String op) {
        return http.get().uri(bankUrl + "/status/" + txnId + "?op=" + op)
                .retrieve().body(StatusResponse.class).status();
    }

    private static List<TxnEventView> events(String txnId) {
        return http.get().uri(system.switchUrl() + "/txns/" + txnId)
                .retrieve().body(TxnDetails.class).events();
    }

    /** The states the payment passed through, read from the switch's txn_events history. */
    private static List<String> path(String txnId) {
        return events(txnId).stream().map(TxnEventView::toState).toList();
    }

    /**
     * Where all the money is, in paise.
     *
     * @param customersA  total of every customer balance at Bank A
     * @param settlementA what Bank A owes the switch (negative = the switch owes Bank A)
     */
    private record Money(long customersA, long settlementA, long customersB, long settlementB) {

        static Money snapshot() {
            return new Money(customers(system.bankADb()), settlement(system.bankADb()),
                    customers(system.bankBDb()), settlement(system.bankBDb()));
        }

        // Customer and settlement accounts are liabilities: natural balance = -raw_balance.
        private static long customers(JdbcClient db) {
            return db.sql("SELECT COALESCE(SUM(-raw_balance), 0) FROM accounts WHERE vpa IS NOT NULL")
                    .query(Long.class).single();
        }

        private static long settlement(JdbcClient db) {
            return db.sql("SELECT -raw_balance FROM accounts WHERE id = 'SWITCH_SETTLEMENT'")
                    .query(Long.class).single();
        }
    }
}
