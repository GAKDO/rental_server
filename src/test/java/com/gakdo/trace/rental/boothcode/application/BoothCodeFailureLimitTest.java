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
        // 가짜 시계의 "지금" 시각. 처음엔 2026-11-12 00:00 UTC로 고정
        private Instant now = Instant.parse("2026-11-12T00:00:00Z");

        // 테스트가 시간을 원하는 만큼 앞으로 옮길 때 부르는 메서드
        void advance(Duration d) {
            // 지금 시각에 받은 시간만큼 더한다
            now = now.plus(d);
        }

        // (도구) @Override = 부모 클래스(Clock)의 메서드를 이 클래스에서 다시 정의한다는 표시
        @Override
        // 시계의 시간대를 알려 주는 메서드
        public ZoneId getZone() {
            // 시간대는 UTC로 고정한다
            return ZoneId.of("UTC");
        }

        @Override
        // 다른 시간대 시계를 달라는 요청. 시험에선 필요 없어 자기 자신을 그대로 돌려준다
        public Clock withZone(ZoneId zone) {
            // 같은 가짜 시계를 돌려준다
            return this;
        }

        @Override
        // "지금 몇 시냐"는 질문에 답하는 메서드 (운영 코드가 이걸 불러 시각을 읽는다)
        public Instant instant() {
            // 테스트가 정해 둔 지금 시각을 돌려준다
            return now;
        }
    }

    // [상황] 1분 안에 31번 시도한다.
    // [기대] 30번까지 허락, 31번째는 조회하기 전에 막고 "60초 뒤"를 알린다. 59초 뒤엔 "1초", 60초 뒤엔 다시 허락.
    // (도구) @Test = JUnit에게 "이 메서드가 테스트 하나"라고 알리는 표시. @DisplayName = 결과 화면에 보일 한글 이름
    @Test
    @DisplayName("1분에 30번까지는 조회를 허락하고, 31번째는 조회 전에 막아 1분 뒤 풀린다")
    void blocksThirtyFirstAttemptBeforeLookup() {
        // 준비: 시간을 직접 옮길 수 있는 가짜 시계를 만든다 (1분을 실제로 기다리지 않으려고)
        MovableClock clock = new MovableClock();
        // 준비: 그 시계를 쓰는 시도 횟수 제한기를 만든다
        BoothCodeFailureLimit limit = new BoothCodeFailureLimit(clock);
        // 실행: 한도(30번)만큼 되풀이한다
        for (int i = 0; i < BoothCodeFailureLimit.MAX_FAILURES; i++) {
            // (도구) assertThat(값).isZero() = 값이 0이어야 한다는 검사. tryAcquire()는 0이면 "조회 허락", 0보다 크면 "막힘, 남은 초"
            // 실행+확인: 30번째까지는 매번 0(허락)이어야 한다 (틀린 시도로 세어 둠)
            assertThat(limit.tryAcquire()).isZero();
        }
        // 실행+확인: 31번째는 막히고 "60초 뒤에 다시"라는 뜻으로 60이 나와야 한다
        assertThat(limit.tryAcquire()).isEqualTo(60);
        // 실행: 가짜 시계를 59초 앞으로 옮긴다
        clock.advance(Duration.ofSeconds(59));
        // 실행+확인: 아직 막혀 있고 남은 시간 1초가 나와야 한다
        assertThat(limit.tryAcquire()).isEqualTo(1);
        // 실행: 1초 더 옮겨 막은 지 딱 60초가 되게 한다
        clock.advance(Duration.ofSeconds(1));
        // 실행+확인: 막힘이 풀려 다시 0(허락)이어야 한다
        assertThat(limit.tryAcquire()).isZero();
    }

    // [상황] 맞는 코드(또는 만료 코드)로 100번 조회한다.
    // [기대] 있는 코드였으니 실패로 세지 않아 계속 허락한다.
    @Test
    @DisplayName("있는 코드(맞음·만료)로 되돌린 조회는 실패로 세지 않는다")
    void releasedAttemptsDoNotCount() {
        // 준비: 가짜 시계를 쓰는 시도 횟수 제한기를 만든다
        BoothCodeFailureLimit limit = new BoothCodeFailureLimit(new MovableClock());
        // 실행: 100번 되풀이한다 (한도 30번보다 훨씬 많이)
        for (int i = 0; i < 100; i++) {
            // 실행+확인: 매번 조회가 허락(0)돼야 한다
            assertThat(limit.tryAcquire()).isZero();
            // 실행: 있는 코드였다고 되돌린다 = 방금 시도를 실패 횟수에서 뺀다 (맞는 코드 조회는 공격이 아님)
            limit.release();
        }
    }

    // [상황] 30번 틀린 뒤 1분이 지난다.
    // [기대] 다시 처음부터 센다.
    @Test
    @DisplayName("1분이 지나면 틀린 횟수를 다시 센다")
    void windowResets() {
        // 준비: 가짜 시계를 만든다
        MovableClock clock = new MovableClock();
        // 준비: 그 시계를 쓰는 시도 횟수 제한기를 만든다
        BoothCodeFailureLimit limit = new BoothCodeFailureLimit(clock);
        // 준비: 한도(30번)만큼 되풀이한다
        for (int i = 0; i < BoothCodeFailureLimit.MAX_FAILURES; i++) {
            // 준비: 틀린 시도를 한 번 쌓는다 (되돌리지 않음)
            limit.tryAcquire();
        }
        // 실행: 가짜 시계를 1분 앞으로 옮긴다 (세는 구간이 끝남)
        clock.advance(Duration.ofMinutes(1));
        // 실행+확인: 새 구간이 시작돼 횟수를 처음부터 세므로 허락(0)이어야 한다
        assertThat(limit.tryAcquire()).isZero();
    }

    // [상황] 동시에 100개 시도가 들어온다.
    // [기대] 허락받는 것은 정확히 30개뿐이다(동시 요청으로 한도를 넘지 못함).
    @Test
    @DisplayName("동시에 100개가 와도 한 구간에 조회를 허락받는 것은 30개뿐이다")
    void concurrentAttemptsAreCappedAtThirty() throws Exception {
        // 준비: 가짜 시계를 쓰는 시도 횟수 제한기를 만든다
        BoothCodeFailureLimit limit = new BoothCodeFailureLimit(new MovableClock());
        // 준비: 허락받은 횟수를 셀 계수기. 여러 스레드가 동시에 올려도 틀리지 않는 AtomicInteger를 쓴다
        java.util.concurrent.atomic.AtomicInteger allowed = new java.util.concurrent.atomic.AtomicInteger();
        // 준비: 동시에 일할 스레드 16개짜리 작업 풀을 만든다 (try(...) = 끝나면 풀을 자동으로 닫음)
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(16)) {
            // 준비: 맡긴 작업들의 "끝났는지 확인표(Future)"를 모을 목록
            java.util.List<java.util.concurrent.Future<?>> futures = new java.util.ArrayList<>();
            // 실행: 작업 100개를 맡긴다
            for (int i = 0; i < 100; i++) {
                // 실행: 작업 하나를 풀에 맡기고 확인표를 목록에 넣는다
                futures.add(pool.submit(() -> {
                    // 실행: 조회 허락(0)을 받았으면
                    if (limit.tryAcquire() == 0) {
                        // 허락 횟수를 1 올린다
                        allowed.incrementAndGet();
                    }
                }));
            }
            // 실행: 맡긴 작업 하나하나에 대해
            for (var f : futures) {
                // 그 작업이 끝날 때까지 기다린다 (100개가 다 끝난 뒤 확인하려고)
                f.get();
            }
        }
        // 확인: 동시에 몰려도 허락받은 수는 정확히 30이어야 한다 (동시 요청으로 한도를 뚫지 못함)
        assertThat(allowed.get()).isEqualTo(BoothCodeFailureLimit.MAX_FAILURES);
    }
}
