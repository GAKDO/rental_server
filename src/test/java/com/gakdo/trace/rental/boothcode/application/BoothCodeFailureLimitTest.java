// 이 파일이 확인하는 것: 부스 코드 맞히기 공격 막기. 1분에 틀린 시도가 30번 쌓이면 1분 동안 코드 조회를 막는다.

package com.gakdo.trace.rental.boothcode.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class BoothCodeFailureLimitTest {

    /** 시험에서 시간을 직접 옮기는 시계 */
    // 도우미: 시간을 직접 앞으로 옮기는 가짜 시계.
    private static final class MovableClock extends Clock {
        private Instant now = Instant.parse("2026-11-12T00:00:00Z");

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    // [상황] 1분 안에 31번 시도한다.
    // [기대] 30번까지 허락, 31번째는 조회하기 전에 막고 "60초 뒤"를 알린다. 59초 뒤엔 "1초", 60초 뒤엔 다시 허락.
    @Test
    @DisplayName("1분에 30번까지는 조회를 허락하고, 31번째는 조회 전에 막아 1분 뒤 풀린다")
    void blocksThirtyFirstAttemptBeforeLookup() {
        MovableClock clock = new MovableClock();
        BoothCodeFailureLimit limit = new BoothCodeFailureLimit(clock);
        for (int i = 0; i < BoothCodeFailureLimit.MAX_FAILURES; i++) {
            assertThat(limit.tryAcquire()).isZero();
        }
        assertThat(limit.tryAcquire()).isEqualTo(60);
        clock.advance(Duration.ofSeconds(59));
        assertThat(limit.tryAcquire()).isEqualTo(1);
        clock.advance(Duration.ofSeconds(1));
        assertThat(limit.tryAcquire()).isZero();
    }

    // [상황] 맞는 코드(또는 만료 코드)로 100번 조회한다.
    // [기대] 있는 코드였으니 실패로 세지 않아 계속 허락한다.
    @Test
    @DisplayName("있는 코드(맞음·만료)로 되돌린 조회는 실패로 세지 않는다")
    void releasedAttemptsDoNotCount() {
        BoothCodeFailureLimit limit = new BoothCodeFailureLimit(new MovableClock());
        for (int i = 0; i < 100; i++) {
            assertThat(limit.tryAcquire()).isZero();
            limit.release();
        }
    }

    // [상황] 30번 틀린 뒤 1분이 지난다.
    // [기대] 다시 처음부터 센다.
    @Test
    @DisplayName("1분이 지나면 틀린 횟수를 다시 센다")
    void windowResets() {
        MovableClock clock = new MovableClock();
        BoothCodeFailureLimit limit = new BoothCodeFailureLimit(clock);
        for (int i = 0; i < BoothCodeFailureLimit.MAX_FAILURES; i++) {
            limit.tryAcquire();
        }
        clock.advance(Duration.ofMinutes(1));
        assertThat(limit.tryAcquire()).isZero();
    }

    // [상황] 동시에 100개 시도가 들어온다.
    // [기대] 허락받는 것은 정확히 30개뿐이다(동시 요청으로 한도를 넘지 못함).
    @Test
    @DisplayName("동시에 100개가 와도 한 구간에 조회를 허락받는 것은 30개뿐이다")
    void concurrentAttemptsAreCappedAtThirty() throws Exception {
        BoothCodeFailureLimit limit = new BoothCodeFailureLimit(new MovableClock());
        java.util.concurrent.atomic.AtomicInteger allowed = new java.util.concurrent.atomic.AtomicInteger();
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(16)) {
            java.util.List<java.util.concurrent.Future<?>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < 100; i++) {
                futures.add(pool.submit(() -> {
                    if (limit.tryAcquire() == 0) {
                        allowed.incrementAndGet();
                    }
                }));
            }
            for (var f : futures) {
                f.get();
            }
        }
        assertThat(allowed.get()).isEqualTo(BoothCodeFailureLimit.MAX_FAILURES);
    }
}
