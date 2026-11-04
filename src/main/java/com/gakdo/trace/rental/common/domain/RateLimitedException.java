package com.gakdo.trace.rental.common.domain;

/** 429 RATE_LIMITED. ProblemAdvice가 Retry-After 머리글을 붙인다. */
public class RateLimitedException extends DomainException {

    private final long retryAfterSeconds;

    public RateLimitedException(String detail, long retryAfterSeconds) {
        super(ErrorCode.RATE_LIMITED, detail);
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
