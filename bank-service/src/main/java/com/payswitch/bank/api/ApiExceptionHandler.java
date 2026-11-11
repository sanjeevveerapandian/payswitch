package com.payswitch.bank.api;

import com.payswitch.bank.service.UnknownVpaException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * Turns exceptions into clear HTTP answers (RFC 7807 "problem detail" JSON) instead of a bare 500.
 * Business declines never reach here: they are normal 200 responses with result=DECLINED.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    /** Bad JSON, or a request record's constructor refused it (e.g. amount = 0). */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ProblemDetail badBody(HttpMessageNotReadableException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, rootMessage(e));
    }

    /** e.g. GET /status/t1?op=WITHDRAW */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ProblemDetail badParameter(MethodArgumentTypeMismatchException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                "Bad value for " + e.getName() + ": " + e.getValue());
    }

    @ExceptionHandler(UnknownVpaException.class)
    public ProblemDetail unknownVpa(UnknownVpaException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    }

    private static String rootMessage(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        return root.getMessage();
    }
}
