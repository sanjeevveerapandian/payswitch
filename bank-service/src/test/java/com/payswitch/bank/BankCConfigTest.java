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

/** Phase 9: the same app started as BANKC loads Bank C's customers. (Own context + own DB.) */
@SpringBootTest(properties = "bank.code=BANKC")
@Import(TestcontainersConfig.class)
class BankCConfigTest {

    @Autowired
    private BankOperationsService bank;

    @Autowired
    private LedgerAuditService audit;

    @Test
    void bankCHasItsOwnSeedCustomers() {
        assertEquals(400_000, bank.balance("kumar@bankc").balance());
        assertEquals(250_000, bank.balance("priya@bankc").balance());
        assertThrows(UnknownVpaException.class, () -> bank.balance("anu@bankb"));
        audit.check();
    }
}
