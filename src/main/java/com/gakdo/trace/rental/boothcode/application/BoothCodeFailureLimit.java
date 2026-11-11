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

    /**
     * 조회 한 번을 실패로 미리 센다(DB를 읽기 전에). 동시에 여러 요청이 와도 한 구간에 30번을 넘는 조회는 DB에 닿지 않는다.
     * 코드가 DB에 있으면(맞음·만료·사용됨) {@link #release()}로 되돌린다.
     *
     * @return 0이면 조회해도 된다. 0보다 크면 막혔고, 다시 시도할 때까지 남은 초
     */
    public synchronized long tryAcquire() {
        Instant now = clock.instant();
        if (now.isBefore(blockedUntil)) {
            return Math.max(1, Duration.between(now, blockedUntil).toSeconds());
        }
        if (!now.isBefore(windowStart.plus(WINDOW))) {
            windowStart = now;
            failures = 0;
        }
        if (failures >= MAX_FAILURES) {
            blockedUntil = now.plus(BLOCK);
            windowStart = blockedUntil;
            failures = 0;
            return BLOCK.toSeconds();
        }
        failures++;
        return 0;
    }

    /** 미리 센 조회가 틀린 코드가 아니었으면 되돌린다. */
    public synchronized void release() {
        if (failures > 0) {
            failures--;
        }
    }
}
