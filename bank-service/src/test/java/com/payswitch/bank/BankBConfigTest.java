package com.payswitch.bank;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.payswitch.bank.service.BankOperationsService;
import com.payswitch.bank.service.LedgerAuditService;
import com.payswitch.bank.service.UnknownVpaException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/** The same app started as BANKB loads Bank B's customers, not Bank A's. (Own context + own DB.) */
@SpringBootTest(properties = "bank.code=BANKB")
@Import(TestcontainersConfig.class)
class BankBConfigTest {

    @Autowired
    private BankOperationsService bank;

    @Autowired
    private LedgerAuditService audit;

    @Test
    void bankBHasItsOwnSeedCustomers() {
        assertEquals(100_000, bank.balance("anu@bankb").balance());
        assertEquals(300_000, bank.balance("kiran@bankb").balance());
        assertThrows(UnknownVpaException.class, () -> bank.balance("ravi@banka"));
        audit.check();
    }
}
