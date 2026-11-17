package com.payswitch.bank;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.payswitch.bank.recon.ReconQueryService;
import com.payswitch.common.bank.CreditRequest;
import com.payswitch.common.bank.DebitRequest;
import com.payswitch.common.bank.OpType;
import com.payswitch.common.bank.ReconEntry;
import com.payswitch.common.bank.ReconPage;
import com.payswitch.common.bank.ReconStatus;
import com.payswitch.common.bank.ReverseRequest;
import com.payswitch.common.bank.StatusCheckRequest;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;

/** GET /recon/entries: what the bank tells reconciliation about switch operations. */
class ReconEntriesTest extends BankIntegrationTest {

    @Autowired
    private ReconQueryService recon;

    @Autowired
    private TestRestTemplate http;

    /** The database's clock, which stamps created_at. (The test JVM's clock may drift from it.) */
    private Instant dbNow() {
        return jdbc.sql("SELECT clock_timestamp()").query(OffsetDateTime.class).single().toInstant();
    }

    private List<ReconEntry> all(Instant from, Instant to) {
        return recon.entries(from, to, 0, ReconQueryService.MAX_PAGE_SIZE).entries();
    }

    @Test
    void everyKindOfOperationIsReportedWithItsStatusAndAmount() {
        String ravi = newCustomer(500_000);
        String paid = newTxnId();
        String poor = newTxnId();
        String refunded = newTxnId();
        String neverDebited = newTxnId();
        String fenced = newTxnId();
        String credited = newTxnId();
        Instant from = dbNow();

        bank.debit(new DebitRequest(paid, ravi, 20_000));
        bank.debit(new DebitRequest(poor, ravi, 9_999_999));
        bank.debit(new DebitRequest(refunded, ravi, 15_000));
        bank.reverse(new ReverseRequest(refunded));
        bank.reverse(new ReverseRequest(neverDebited));
        bank.statusCheck(new StatusCheckRequest(fenced, OpType.DEBIT, true));
        bank.credit(new CreditRequest(credited, ravi, 5_000));

        Map<String, ReconEntry> got = all(from, dbNow()).stream()
                .collect(Collectors.toMap(e -> e.txnId() + "/" + e.op(), e -> e));

        assertEntry(got, paid, OpType.DEBIT, ReconStatus.SUCCESS, 20_000);
        assertEntry(got, poor, OpType.DEBIT, ReconStatus.DECLINED, 0);
        assertEntry(got, refunded, OpType.DEBIT, ReconStatus.SUCCESS, 15_000);
        assertEntry(got, refunded, OpType.REVERSE, ReconStatus.SUCCESS, 15_000);
        assertEntry(got, neverDebited, OpType.REVERSE, ReconStatus.NO_DEBIT, 0);
        assertEntry(got, neverDebited, OpType.DEBIT, ReconStatus.FENCED, 0);   // the reverse's tombstone
        assertEntry(got, fenced, OpType.DEBIT, ReconStatus.FENCED, 0);
        assertEntry(got, credited, OpType.CREDIT, ReconStatus.SUCCESS, 5_000);
    }

    @Test
    void onlyTheWindowIsReturnedFromInclusiveToExclusive() {
        String ravi = newCustomer(500_000);
        String before = newTxnId();
        String inside = newTxnId();
        String after = newTxnId();

        bank.debit(new DebitRequest(before, ravi, 100));
        Instant from = dbNow();
        bank.debit(new DebitRequest(inside, ravi, 100));
        Instant to = dbNow();
        bank.debit(new DebitRequest(after, ravi, 100));

        List<String> ids = all(from, to).stream().map(ReconEntry::txnId).toList();

        assertTrue(ids.contains(inside));
        assertFalse(ids.contains(before));
        assertFalse(ids.contains(after));
    }

    @Test
    void pagesAreStableAndTogetherGiveEverything() {
        String ravi = newCustomer(1_000_000);
        Instant from = dbNow();
        for (int i = 0; i < 7; i++) {
            bank.debit(new DebitRequest(newTxnId(), ravi, 100));
        }
        Instant to = dbNow();

        List<ReconEntry> pagedTogether = new ArrayList<>();
        ReconPage page;
        int pageNo = 0;
        do {
            page = recon.entries(from, to, pageNo++, 3);
            pagedTogether.addAll(page.entries());
        } while (page.hasMore());

        assertEquals(all(from, to), pagedTogether);
        assertTrue(pageNo >= 3, "7 entries in pages of 3 must take at least 3 pages");
        // Sorted by createdAt, then txnId.
        for (int i = 1; i < pagedTogether.size(); i++) {
            ReconEntry a = pagedTogether.get(i - 1);
            ReconEntry b = pagedTogether.get(i);
            assertTrue(a.createdAt().isBefore(b.createdAt())
                    || (a.createdAt().equals(b.createdAt()) && a.txnId().compareTo(b.txnId()) <= 0));
        }
    }

    @Test
    void readingChangesNothing() {
        Instant to = dbNow();
        long rowsBefore = jdbc.sql("SELECT COUNT(*) FROM processed_requests").query(Long.class).single();

        recon.entries(to.minusSeconds(3600), to, 0, 100);

        assertEquals(rowsBefore, jdbc.sql("SELECT COUNT(*) FROM processed_requests").query(Long.class).single());
    }

    @Test
    void badQueriesAreA400() {
        String now = Instant.now().toString();
        String hourAgo = Instant.now().minusSeconds(3600).toString();

        assertEquals(HttpStatus.BAD_REQUEST,
                http.getForEntity("/recon/entries?from=" + now + "&to=" + hourAgo, String.class).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST,
                http.getForEntity("/recon/entries?from=" + hourAgo + "&to=" + now + "&size=0", String.class).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST,
                http.getForEntity("/recon/entries?from=" + hourAgo + "&to=" + now + "&size=1001", String.class).getStatusCode());
        assertEquals(HttpStatus.OK,
                http.getForEntity("/recon/entries?from=" + hourAgo + "&to=" + now, String.class).getStatusCode());
    }

    private static void assertEntry(Map<String, ReconEntry> got, String txnId, OpType op, ReconStatus status, long amount) {
        ReconEntry e = got.get(txnId + "/" + op);
        assertTrue(e != null, "missing " + txnId + "/" + op);
        assertEquals(status, e.status(), txnId + "/" + op);
        assertEquals(amount, e.amount(), txnId + "/" + op);
    }
}
