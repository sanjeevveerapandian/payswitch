package com.payswitch.switchservice.live;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * GET /events/txns: a Server-Sent Events stream. The response never ends; the server keeps writing
 * "event: snapshot" once, then "event: txns" batches and ": hb" heartbeats. The browser reads it with
 * {@code new EventSource(url)}, which also reconnects by itself if the connection drops.
 */
@RestController
public class LiveController {

    private final TxnEventStream stream;

    public LiveController(TxnEventStream stream) {
        this.stream = stream;
    }

    @GetMapping(path = "/events/txns", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter txns() {
        return stream.subscribe();
    }

    @ExceptionHandler(TxnEventStream.TooManySubscribersException.class)
    public ProblemDetail tooMany(TxnEventStream.TooManySubscribersException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
    }
}
