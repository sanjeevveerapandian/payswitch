package com.payswitch.psp;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.HttpClientErrorException;

/** Turns exceptions into clear HTTP answers (RFC 7807 "problem detail" JSON). */
@RestControllerAdvice
public class ApiExceptionHandler {

    /** Bad amount, or a missing field. Nothing was sent to the switch. */
    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail badRequest(IllegalArgumentException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ProblemDetail badJson(HttpMessageNotReadableException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Request body is not valid JSON");
    }

    /** The switch itself said 4xx (malformed VPA -> 400, unknown txnId -> 404): pass it on. */
    @ExceptionHandler(HttpClientErrorException.class)
    public ProblemDetail switchRejected(HttpClientErrorException e) {
        ProblemDetail fromSwitch = e.getResponseBodyAs(ProblemDetail.class);
        String detail = fromSwitch != null && fromSwitch.getDetail() != null
                ? fromSwitch.getDetail() : e.getStatusText();
        return ProblemDetail.forStatusAndDetail(e.getStatusCode(), detail);
    }

    /** 502 Bad Gateway: "the server I depend on failed". Body carries the txnId to check later. */
    @ExceptionHandler(SwitchUnavailableException.class)
    public ProblemDetail switchUnavailable(SwitchUnavailableException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY, e.getMessage());
        problem.setProperty("txnId", e.txnId());
        return problem;
    }
}
