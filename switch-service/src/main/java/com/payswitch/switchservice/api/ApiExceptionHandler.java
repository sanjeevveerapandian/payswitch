package com.payswitch.switchservice.api;

import com.payswitch.switchservice.service.TransactionNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Turns exceptions into clear HTTP answers (RFC 7807 "problem detail" JSON). */
@RestControllerAdvice
public class ApiExceptionHandler {

    /** Bad JSON, or ReqPay's constructor refused it (e.g. amount = 0). */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ProblemDetail badBody(HttpMessageNotReadableException e) {
        Throwable root = e;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, root.getMessage());
    }

    /** A malformed VPA, or payer = payee. Nothing was saved. */
    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail badRequest(IllegalArgumentException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(TransactionNotFoundException.class)
    public ProblemDetail notFound(TransactionNotFoundException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    }
}
