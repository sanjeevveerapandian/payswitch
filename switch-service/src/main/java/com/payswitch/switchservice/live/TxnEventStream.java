package com.payswitch.switchservice.live;

import com.payswitch.switchservice.db.TransactionRepository;
import com.payswitch.switchservice.db.TxnEventRepository;
import com.payswitch.switchservice.domain.Txn;
import com.payswitch.switchservice.domain.TxnEvent;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * The live feed behind GET /events/txns (Server-Sent Events).
 *
 * <p>How it works:
 * <ol>
 *   <li>Every committed state change lands in {@link #onCommitted} (AFTER_COMMIT) and is only queued:
 *       the committing thread is a payment thread and must not wait on slow browsers.</li>
 *   <li>Every 200 ms ONE thread drains the queue and sends the whole batch to every subscriber as a
 *       single SSE event. 200 payments/s become 5 messages/s per browser, not 1,000.</li>
 *   <li>Every 15 s it sends a comment line (a heartbeat), so proxies do not close an idle connection
 *       and a dead client is noticed: writing to it fails.</li>
 *   <li>A subscriber is removed on completion, timeout, error, or the first failed write. Nothing holds
 *       a thread per client, so a closed browser tab leaks neither a thread nor memory.</li>
 * </ol>
 *
 * <p>Its own scheduler thread, on purpose: Spring's shared @Scheduled pool has ONE thread, and the
 * resolver and a long recon run use it too. The live feed must not freeze while recon runs.
 *
 * <p><b>One switch instance only.</b> Events live in this JVM's memory. With several switch
 * instances behind a load balancer, a browser connected to instance 1 would never see payments
 * handled by instance 2. That needs pub/sub between instances (e.g. Postgres LISTEN/NOTIFY, or Redis
 * pub/sub): out of scope for this simulator.
 */
@Component
public class TxnEventStream {

    private static final Logger log = LoggerFactory.getLogger(TxnEventStream.class);

    /** If nobody reads for a while, keep at most this many events (the oldest are dropped). */
    static final int MAX_QUEUED = 10_000;

    private final TransactionRepository txns;
    private final TxnEventRepository events;
    private final Duration batchInterval;
    private final Duration heartbeatInterval;
    private final int snapshotSize;
    private final int maxSubscribers;

    private final List<SseEmitter> subscribers = new CopyOnWriteArrayList<>();
    private final ConcurrentLinkedQueue<TxnStateChanged> queue = new ConcurrentLinkedQueue<>();
    private final AtomicInteger queued = new AtomicInteger();
    private ScheduledExecutorService timer;

    public TxnEventStream(TransactionRepository txns, TxnEventRepository events,
                          @Value("${switch.live.batch-interval:200ms}") Duration batchInterval,
                          @Value("${switch.live.heartbeat-interval:15s}") Duration heartbeatInterval,
                          @Value("${switch.live.snapshot-size:50}") int snapshotSize,
                          @Value("${switch.live.max-subscribers:100}") int maxSubscribers) {
        this.txns = txns;
        this.events = events;
        this.batchInterval = batchInterval;
        this.heartbeatInterval = heartbeatInterval;
        this.snapshotSize = snapshotSize;
        this.maxSubscribers = maxSubscribers;
    }

    @PostConstruct
    void start() {
        timer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "sse-batcher");
            t.setDaemon(true);
            return t;
        });
        timer.scheduleWithFixedDelay(this::flush, batchInterval.toMillis(), batchInterval.toMillis(),
                TimeUnit.MILLISECONDS);
        timer.scheduleWithFixedDelay(this::heartbeat, heartbeatInterval.toMillis(), heartbeatInterval.toMillis(),
                TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    void stop() {
        timer.shutdownNow();
        subscribers.forEach(SseEmitter::complete);
        subscribers.clear();
    }

    // ---- in: committed state changes --------------------------------------------------------

    /**
     * Called only after the transaction that made the change has COMMITTED. Before that, the change
     * may still roll back, and a browser must never show a state the database does not have.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCommitted(TxnStateChanged event) {
        if (subscribers.isEmpty()) {
            return;                                    // nobody is watching: keep nothing
        }
        queue.add(event);
        if (queued.incrementAndGet() > MAX_QUEUED && queue.poll() != null) {
            queued.decrementAndGet();                  // a slow or stuck flush: drop the oldest
        }
    }

    // ---- out: subscribers -------------------------------------------------------------------

    /**
     * A new browser: register it FIRST, then send the snapshot. Changes committed in between may
     * reach it twice (in the snapshot and in the next batch); the page ignores a step it already has.
     * The other order could lose them.
     *
     * @throws TooManySubscribersException over the limit (answered 503)
     */
    public SseEmitter subscribe() {
        if (subscribers.size() >= maxSubscribers) {
            throw new TooManySubscribersException(maxSubscribers);
        }
        SseEmitter emitter = new SseEmitter(Duration.ofMinutes(30).toMillis());  // the browser reconnects after
        emitter.onCompletion(() -> subscribers.remove(emitter));
        emitter.onTimeout(() -> subscribers.remove(emitter));
        emitter.onError(e -> subscribers.remove(emitter));
        subscribers.add(emitter);
        try {
            emitter.send(SseEmitter.event().name("snapshot").data(snapshot()));
        } catch (IOException | IllegalStateException e) {
            subscribers.remove(emitter);
        }
        return emitter;
    }

    public int subscriberCount() {
        return subscribers.size();
    }

    /** The newest payments with their state path, oldest step first. Read from the database. */
    public List<TxnView> snapshot() {
        List<Txn> latest = txns.latest(snapshotSize);
        Map<String, List<TxnEvent>> byTxn = events.findByTxnIds(latest.stream().map(Txn::txnId).toList())
                .stream().collect(Collectors.groupingBy(TxnEvent::txnId));
        List<TxnView> views = new ArrayList<>();
        for (Txn t : latest) {
            List<TxnView.Step> path = byTxn.getOrDefault(t.txnId(), List.of()).stream()
                    .filter(e -> e.fromState() != e.toState())          // notes do not change the state
                    .map(e -> new TxnView.Step(e.toState().name(), e.reason(), e.createdAt()))
                    .toList();
            views.add(new TxnView(t.txnId(), t.payerVpa(), t.payeeVpa(), t.amount(), t.state().name(),
                    t.createdAt(), path));
        }
        return views;
    }

    /** Sends everything queued since the last batch, as one event. Package-private for tests. */
    void flush() {
        try {
            List<TxnStateChanged> batch = new ArrayList<>();
            for (TxnStateChanged e; batch.size() < MAX_QUEUED && (e = queue.poll()) != null; ) {
                queued.decrementAndGet();
                batch.add(e);
            }
            if (!batch.isEmpty()) {
                broadcast(SseEmitter.event().name("txns").data(batch));
            }
        } catch (RuntimeException e) {
            log.warn("Live feed flush failed: {}", e.toString());   // never kill the timer thread
        }
    }

    /** A comment line (": hb"): browsers ignore it; proxies see traffic; dead clients fail to write. */
    void heartbeat() {
        try {
            broadcast(SseEmitter.event().comment("hb " + Instant.now()));
        } catch (RuntimeException e) {
            log.warn("Live feed heartbeat failed: {}", e.toString());
        }
    }

    private void broadcast(SseEmitter.SseEventBuilder event) {
        for (SseEmitter emitter : subscribers) {
            try {
                emitter.send(event);
            } catch (IOException | IllegalStateException gone) {
                // The browser went away (tab closed, network lost). Forget it now: no retries, no leak.
                subscribers.remove(emitter);
                try {
                    emitter.completeWithError(gone);
                } catch (RuntimeException alreadyClosed) {
                    // nothing left to clean up
                }
            }
        }
    }

    /** More browsers than allowed are watching. */
    public static class TooManySubscribersException extends RuntimeException {
        public TooManySubscribersException(int max) {
            super("Too many live monitors open (max " + max + "); try again later");
        }
    }
}
