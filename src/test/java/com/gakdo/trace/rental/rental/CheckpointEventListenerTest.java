// 이 파일이 확인하는 것: 다른 서버의 이벤트를 받아 시점 상태를 옮기는 부분.
// 사진 업로드됨 → 촬영됨(CAPTURED), 등급 매김 → 등급(GRADED), 봉인됨 → 봉인(SEALED).

package com.gakdo.trace.rental.rental;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gakdo.trace.rental.rental.application.CheckpointAdvance;
import com.gakdo.trace.rental.rental.application.CheckpointProgressService;
import com.gakdo.trace.rental.rental.domain.CheckpointStatus;
import com.gakdo.trace.rental.rental.infra.CheckpointEventListener;

class CheckpointEventListenerTest {

    // 테스트 이벤트에 넣을 렌탈 공개 ID (21자)
    private static final String RENTAL = "IPv6mcQOlTkKj6mOecAQt";
    // 테스트 이벤트에 넣을 시점 공개 ID (21자)
    private static final String CHECKPOINT = "V1StGXR8_Z5jdHi6B-myT";

    // (도구) mock(클래스) = 진짜 대신 쓰는 가짜 객체(목). 시키지 않은 질문엔 빈 값을 돌려주고, 불린 기록을 남긴다
    // 준비: 시점 진행 서비스를 가짜로 만든다 (리스너가 이벤트를 제대로 해석해 넘기는지만 보려고)
    private final CheckpointProgressService progress = mock(CheckpointProgressService.class);
    // 준비: 시험할 대상인 이벤트 리스너를 가짜 서비스와 JSON 변환기(ObjectMapper)로 만든다
    private final CheckpointEventListener listener = new CheckpointEventListener(progress, new ObjectMapper());

    // [상황] 사진 업로드·등급(B)·봉인 이벤트가 차례로 온다.
    // [기대] 시점이 각각 촬영됨·등급(B 기록)·봉인으로 옮겨진다.
    // (도구) @Test = JUnit에게 "이 메서드가 테스트 하나"라고 알리는 표시. @DisplayName = 결과 화면에 보일 한글 이름
    @Test
    @DisplayName("라우팅 키마다 시점 상태가 정해진다(uploaded→CAPTURED, graded→GRADED, sealed→SEALED)")
    void routingKeys() {
        // (도구) when(가짜.메서드(조건)).thenReturn(결과) = "이 질문이 오면 이 결과를 돌려줘"라고 미리 정해 두는 것. any() = 아무 값이나
        // 준비: 가짜 서비스가 어떤 값으로 불려도 "바뀜(CHANGED)"을 돌려주게 한다
        when(progress.advance(any(), any(), any(), any(), any())).thenReturn(CheckpointAdvance.CHANGED);
        // (도구) assertThat(값).isTrue() = 값이 참(true)이어야 한다는 검사
        // 실행+확인: "사진 업로드됨" 경로 이름으로 이벤트를 넘기면 상태가 바뀌었다는 뜻으로 true여야 한다
        assertThat(listener.handle("trace.capture.uploaded", event())).isTrue();
        // (도구) verify(가짜).메서드(값들) = 가짜의 그 메서드가 정확히 이 값들로 불렸어야 한다는 검사
        // 확인: 서비스가 이 렌탈·시점, 촬영됨(CAPTURED), 이벤트의 발생 시각, 등급 없음(null)으로 불렸어야 한다
        verify(progress).advance(RENTAL, CHECKPOINT, CheckpointStatus.CAPTURED,
                OffsetDateTime.parse("2026-11-09T14:05:12+09:00"), null);
        // 실행: "등급 매김" 경로 이름으로 같은 이벤트를 넘긴다
        listener.handle("trace.checkpoint.graded", event());
        // 확인: 서비스가 등급 단계(GRADED)와 이벤트 안의 등급 "B"로 불렸어야 한다 (eq = 정확히 이 값, any = 상관없음)
        verify(progress).advance(eq(RENTAL), eq(CHECKPOINT), eq(CheckpointStatus.GRADED), any(), eq("B"));
        // 실행: "봉인됨" 경로 이름으로 같은 이벤트를 넘긴다
        listener.handle("trace.record.sealed", event());
        // 확인: 서비스가 봉인(SEALED)으로 불렸고, 등급 이벤트가 아니니 등급은 null이어야 한다 (isNull = 값이 null)
        verify(progress).advance(eq(RENTAL), eq(CHECKPOINT), eq(CheckpointStatus.SEALED), any(), isNull());
    }

    // [상황] 이 서버가 다루지 않는 진단 이벤트가 온다(모르는 칸도 섞임).
    // [기대] 건너뛰고 아무것도 바꾸지 않는다.
    @Test
    @DisplayName("모르는 필드는 무시하고, 모르는 라우팅 키는 건너뛴다")
    void lenientConsumer() {
        // 실행+확인: 이 서버가 다루지 않는 진단 이벤트 경로면 건너뛰고 false(바뀐 것 없음)여야 한다
        assertThat(listener.handle("trace.diagnostics.recorded", event())).isFalse();
        // (도구) verifyNoInteractions(가짜) = 그 가짜가 한 번도 불리지 않았어야 한다는 검사
        // 확인: 건너뛰었으니 서비스는 한 번도 불리지 않았어야 한다
        verifyNoInteractions(progress);
    }

    // [상황] JSON이 아니거나, 목록이거나, 시점 번호가 짧거나, 시각이 "어제" 같은 글자다.
    // [기대] 다시 시도하지 않고 실패 보관함(DLX)으로 보낸다.
    @Test
    @DisplayName("봉투가 깨진 이벤트는 재시도 없이 DLX로 보낸다")
    void brokenEnvelopeRejected() {
        // (도구) assertThatThrownBy(코드) = 그 코드를 실행하면 예외가 나야 한다는 검사
        // 실행+확인: JSON이 아닌 글자를 넘기면 예외가 나야 한다
        assertThatThrownBy(() -> listener.handle("trace.record.sealed", "not json"))
                // 확인: 예외 종류가 "다시 시도하지 말고 버려라(실패 보관함 DLX로)"를 뜻하는 AmqpRejectAndDontRequeueException이어야 한다
                .isInstanceOf(AmqpRejectAndDontRequeueException.class);
        // 실행+확인: JSON이지만 객체가 아닌 빈 목록([])을 넘겨도 예외가 나야 한다
        assertThatThrownBy(() -> listener.handle("trace.record.sealed", "[]"))
                // 확인: 이것도 재시도 없이 DLX로 보내는 예외여야 한다
                .isInstanceOf(AmqpRejectAndDontRequeueException.class);
        // 실행+확인: 시점 ID가 21자가 아닌 "short"인 이벤트를 넘기면 예외가 나야 한다
        assertThatThrownBy(() -> listener.handle("trace.record.sealed",
                "{\"rentalId\":\"" + RENTAL + "\",\"checkpointId\":\"short\",\"occurredAt\":\"2026-11-09T14:05:12+09:00\"}"))
                // 확인: 재시도 없이 DLX로 보내는 예외여야 한다
                .isInstanceOf(AmqpRejectAndDontRequeueException.class);
        // 실행+확인: 발생 시각이 날짜 형식이 아닌 "어제"인 이벤트를 넘기면 예외가 나야 한다
        assertThatThrownBy(() -> listener.handle("trace.record.sealed",
                "{\"rentalId\":\"" + RENTAL + "\",\"checkpointId\":\"" + CHECKPOINT + "\",\"occurredAt\":\"어제\"}"))
                // 확인: 재시도 없이 DLX로 보내는 예외여야 한다
                .isInstanceOf(AmqpRejectAndDontRequeueException.class);
        // 확인: 깨진 이벤트는 서비스까지 가지 않았어야 한다 (엉뚱한 값으로 상태가 바뀌지 않음)
        verifyNoInteractions(progress);
    }

    // [상황] 이벤트의 렌탈·시점 짝이 이 서버 기록과 다르거나, 모르는 시점이다.
    // [기대] 다시 시도하지 않고 실패 보관함으로 보낸다(엉뚱한 렌탈 상태를 바꾸지 않게).
    @Test
    @DisplayName("렌탈·시점 짝이 rental-server 기록과 다르거나 모르는 시점이면 재시도 없이 DLX로 보낸다")
    void mismatchedPairRejected() {
        // 준비: 정상 모양의 테스트 이벤트 글자를 만든다
        String body = event();
        // 준비: 가짜 서비스가 "렌탈·시점 짝 불일치"를 돌려주게 한다
        when(progress.advance(any(), any(), any(), any(), any())).thenReturn(CheckpointAdvance.RENTAL_MISMATCH);
        // 실행+확인: 이벤트를 넘기면 예외가 나야 한다
        assertThatThrownBy(() -> listener.handle("trace.capture.uploaded", body))
                // 확인: 짝이 안 맞으면 재시도 없이 DLX로 보내야 한다 (다시 해도 계속 틀리니까)
                .isInstanceOf(AmqpRejectAndDontRequeueException.class);
        // 준비: 이번엔 가짜 서비스가 "모르는 시점"을 돌려주게 바꾼다
        when(progress.advance(any(), any(), any(), any(), any())).thenReturn(CheckpointAdvance.UNKNOWN_CHECKPOINT);
        // 실행+확인: 등급 이벤트를 넘기면 예외가 나야 한다
        assertThatThrownBy(() -> listener.handle("trace.checkpoint.graded", body))
                // 확인: 모르는 시점도 재시도 없이 DLX로 보내야 한다
                .isInstanceOf(AmqpRejectAndDontRequeueException.class);
    }

    // [상황] 이미 옮겨진 시점에 같은 이벤트가 또 온다.
    // [기대] 오류 없이 받아들이고 바뀐 것은 없다.
    @Test
    @DisplayName("이미 옮겨진 시점에 같은 이벤트가 다시 오면 바뀐 것 없이 받아들인다")
    void duplicateAccepted() {
        // 준비: 가짜 서비스가 "바뀐 것 없음(이미 반영됨)"을 돌려주게 한다
        when(progress.advance(any(), any(), any(), any(), any())).thenReturn(CheckpointAdvance.UNCHANGED);
        // 실행+확인: 같은 이벤트가 또 와도 예외 없이 받고, 바뀐 것이 없으니 false여야 한다
        assertThat(listener.handle("trace.capture.uploaded", event())).isFalse();
    }

    // 도우미: 테스트용 이벤트(등급 B, 이 서버가 모르는 칸 somethingNew 포함).
    private static String event() {
        // 이벤트 JSON 글자를 만든다. %s 두 자리에 렌탈 ID·시점 ID를 끼워 넣는다 (formatted)
        return """
                {"schemaVersion":"1.0","eventId":"7uYc0nXq1D2kLm3Np4Qr5","occurredAt":"2026-11-09T14:05:12+09:00",
                 "rentalId":"%s","checkpointId":"%s","grade":"B","somethingNew":true}
                """.formatted(RENTAL, CHECKPOINT);
    }
}
