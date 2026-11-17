package com.growercare.grower.adapters.in.web;

import com.growercare.common.error.ErrorCode;
import com.growercare.common.web.GcProblemHandler.ProblemDetails;
import com.growercare.grower.application.OtpRejectedException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** SPEC-GRW-03 R108: the standard 422 Problem plus {@code attemptsLeft}. */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
class OtpProblemHandler {

    @ExceptionHandler(OtpRejectedException.class)
    ResponseEntity<ProblemDetail> handle(OtpRejectedException e, HttpServletRequest request) {
        var problem = ProblemDetails.of(ErrorCode.BUSINESS_RULE_VIOLATION, e.getMessage(), request);
        problem.setProperty("rule", OtpRejectedException.RULE);
        problem.setProperty("attemptsLeft", e.attemptsLeft());
        return ProblemDetails.response(problem);
    }
}
