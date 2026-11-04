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

    @Test
    @DisplayName("1분에 30번까지 틀려도 막지 않고, 31번째에 1분 동안 막은 뒤 풀린다")
    void blocksAfterThirtyFailuresForOneMinute() {
        MovableClock clock = new MovableClock();
        BoothCodeFailureLimit limit = new BoothCodeFailureLimit(clock);
        for (int i = 0; i < BoothCodeFailureLimit.MAX_FAILURES; i++) {
            limit.recordFailure();
        }
        assertThat(limit.retryAfterSeconds()).isZero();
        limit.recordFailure();
        assertThat(limit.retryAfterSeconds()).isEqualTo(60);
        clock.advance(Duration.ofSeconds(59));
        assertThat(limit.retryAfterSeconds()).isEqualTo(1);
        clock.advance(Duration.ofSeconds(1));
        assertThat(limit.retryAfterSeconds()).isZero();
    }

    @Test
    @DisplayName("1분이 지나면 틀린 횟수를 다시 센다")
    void windowResets() {
        MovableClock clock = new MovableClock();
        BoothCodeFailureLimit limit = new BoothCodeFailureLimit(clock);
        for (int i = 0; i < BoothCodeFailureLimit.MAX_FAILURES; i++) {
            limit.recordFailure();
        }
        clock.advance(Duration.ofMinutes(1));
        limit.recordFailure();
        assertThat(limit.retryAfterSeconds()).isZero();
    }
}
