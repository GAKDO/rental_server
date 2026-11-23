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

    private static final String RENTAL = "IPv6mcQOlTkKj6mOecAQt";
    private static final String CHECKPOINT = "V1StGXR8_Z5jdHi6B-myT";

    private final CheckpointProgressService progress = mock(CheckpointProgressService.class);
    private final CheckpointEventListener listener = new CheckpointEventListener(progress, new ObjectMapper());

    // [상황] 사진 업로드·등급(B)·봉인 이벤트가 차례로 온다.
    // [기대] 시점이 각각 촬영됨·등급(B 기록)·봉인으로 옮겨진다.
    @Test
    @DisplayName("라우팅 키마다 시점 상태가 정해진다(uploaded→CAPTURED, graded→GRADED, sealed→SEALED)")
    void routingKeys() {
        when(progress.advance(any(), any(), any(), any(), any())).thenReturn(CheckpointAdvance.CHANGED);
        assertThat(listener.handle("trace.capture.uploaded", event())).isTrue();
        verify(progress).advance(RENTAL, CHECKPOINT, CheckpointStatus.CAPTURED,
                OffsetDateTime.parse("2026-11-09T14:05:12+09:00"), null);
        listener.handle("trace.checkpoint.graded", event());
        verify(progress).advance(eq(RENTAL), eq(CHECKPOINT), eq(CheckpointStatus.GRADED), any(), eq("B"));
        listener.handle("trace.record.sealed", event());
        verify(progress).advance(eq(RENTAL), eq(CHECKPOINT), eq(CheckpointStatus.SEALED), any(), isNull());
    }

    // [상황] 이 서버가 다루지 않는 진단 이벤트가 온다(모르는 칸도 섞임).
    // [기대] 건너뛰고 아무것도 바꾸지 않는다.
    @Test
    @DisplayName("모르는 필드는 무시하고, 모르는 라우팅 키는 건너뛴다")
    void lenientConsumer() {
        assertThat(listener.handle("trace.diagnostics.recorded", event())).isFalse();
        verifyNoInteractions(progress);
    }

    // [상황] JSON이 아니거나, 목록이거나, 시점 번호가 짧거나, 시각이 "어제" 같은 글자다.
    // [기대] 다시 시도하지 않고 실패 보관함(DLX)으로 보낸다.
    @Test
    @DisplayName("봉투가 깨진 이벤트는 재시도 없이 DLX로 보낸다")
    void brokenEnvelopeRejected() {
        assertThatThrownBy(() -> listener.handle("trace.record.sealed", "not json"))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class);
        assertThatThrownBy(() -> listener.handle("trace.record.sealed", "[]"))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class);
        assertThatThrownBy(() -> listener.handle("trace.record.sealed",
                "{\"rentalId\":\"" + RENTAL + "\",\"checkpointId\":\"short\",\"occurredAt\":\"2026-11-09T14:05:12+09:00\"}"))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class);
        assertThatThrownBy(() -> listener.handle("trace.record.sealed",
                "{\"rentalId\":\"" + RENTAL + "\",\"checkpointId\":\"" + CHECKPOINT + "\",\"occurredAt\":\"어제\"}"))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class);
        verifyNoInteractions(progress);
    }

    // [상황] 이벤트의 렌탈·시점 짝이 이 서버 기록과 다르거나, 모르는 시점이다.
    // [기대] 다시 시도하지 않고 실패 보관함으로 보낸다(엉뚱한 렌탈 상태를 바꾸지 않게).
    @Test
    @DisplayName("렌탈·시점 짝이 rental-server 기록과 다르거나 모르는 시점이면 재시도 없이 DLX로 보낸다")
    void mismatchedPairRejected() {
        String body = event();
        when(progress.advance(any(), any(), any(), any(), any())).thenReturn(CheckpointAdvance.RENTAL_MISMATCH);
        assertThatThrownBy(() -> listener.handle("trace.capture.uploaded", body))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class);
        when(progress.advance(any(), any(), any(), any(), any())).thenReturn(CheckpointAdvance.UNKNOWN_CHECKPOINT);
        assertThatThrownBy(() -> listener.handle("trace.checkpoint.graded", body))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class);
    }

    // [상황] 이미 옮겨진 시점에 같은 이벤트가 또 온다.
    // [기대] 오류 없이 받아들이고 바뀐 것은 없다.
    @Test
    @DisplayName("이미 옮겨진 시점에 같은 이벤트가 다시 오면 바뀐 것 없이 받아들인다")
    void duplicateAccepted() {
        when(progress.advance(any(), any(), any(), any(), any())).thenReturn(CheckpointAdvance.UNCHANGED);
        assertThat(listener.handle("trace.capture.uploaded", event())).isFalse();
    }

    // 도우미: 테스트용 이벤트(등급 B, 이 서버가 모르는 칸 somethingNew 포함).
    private static String event() {
        return """
                {"schemaVersion":"1.0","eventId":"7uYc0nXq1D2kLm3Np4Qr5","occurredAt":"2026-11-09T14:05:12+09:00",
                 "rentalId":"%s","checkpointId":"%s","grade":"B","somethingNew":true}
                """.formatted(RENTAL, CHECKPOINT);
    }
}
