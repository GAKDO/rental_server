package com.gakdo.trace.rental.boothcode.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 부스 코드 대입 방지: 모든 부스를 합쳐 틀린 코드가 1분에 30번을 넘으면 다음 1분 동안 조회를 막는다(계약 redeemBoothCode).
 * rental-server는 한 개만 뜨므로 메모리에 둔다. 다시 시작하면 처음부터 센다.
 */
@Component
public class BoothCodeFailureLimit {

    static final int MAX_FAILURES = 30;
    static final Duration WINDOW = Duration.ofMinutes(1);
    static final Duration BLOCK = Duration.ofMinutes(1);

    private final Clock clock;
    private Instant windowStart = Instant.EPOCH;
    private int failures;
    private Instant blockedUntil = Instant.EPOCH;

    @Autowired
    public BoothCodeFailureLimit() {
        this(Clock.systemUTC());
    }

    BoothCodeFailureLimit(Clock clock) {
        this.clock = clock;
    }

    /** 막혀 있으면 남은 초(1 이상), 아니면 0 */
    public synchronized long retryAfterSeconds() {
        Instant now = clock.instant();
        if (now.isBefore(blockedUntil)) {
            return Math.max(1, Duration.between(now, blockedUntil).toSeconds());
        }
        return 0;
    }

    public synchronized void recordFailure() {
        Instant now = clock.instant();
        if (!now.isBefore(windowStart.plus(WINDOW))) {
            windowStart = now;
            failures = 0;
        }
        failures++;
        if (failures > MAX_FAILURES) {
            blockedUntil = now.plus(BLOCK);
            windowStart = blockedUntil;
            failures = 0;
        }
    }
}
