package com.drawback.review.web;

import org.springframework.core.task.TaskRejectedException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ReviewExceptionHandler {

    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    ProblemDetail optimisticLock() {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "The review was modified concurrently; reload and retry");
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ProblemDetail integrity() {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "Request conflicts with existing data");
    }

    /** Bounded executors reject instead of queueing forever: shed load with a retryable status. */
    @ExceptionHandler(TaskRejectedException.class)
    ProblemDetail overloaded() {
        return ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, "Server is busy, retry shortly");
    }
}
