package com.gakdo.trace.rental.rental;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gakdo.trace.rental.rental.application.CheckpointProgressService;
import com.gakdo.trace.rental.rental.domain.CheckpointStatus;
import com.gakdo.trace.rental.rental.infra.CheckpointEventListener;

class CheckpointEventListenerTest {

    private static final String RENTAL = "IPv6mcQOlTkKj6mOecAQt";
    private static final String CHECKPOINT = "V1StGXR8_Z5jdHi6B-myT";

    private final CheckpointProgressService progress = mock(CheckpointProgressService.class);
    private final CheckpointEventListener listener = new CheckpointEventListener(progress, new ObjectMapper());

    @Test
    @DisplayName("라우팅 키마다 시점 상태가 정해진다(uploaded→CAPTURED, graded→GRADED, sealed→SEALED)")
    void routingKeys() {
        when(progress.advance(any(), any(), any(), any())).thenReturn(true);
        assertThat(listener.handle("trace.capture.uploaded", event())).isTrue();
        verify(progress).advance(RENTAL, CHECKPOINT, CheckpointStatus.CAPTURED,
                OffsetDateTime.parse("2026-11-09T14:05:12+09:00"));
        listener.handle("trace.checkpoint.graded", event());
        verify(progress).advance(eq(RENTAL), eq(CHECKPOINT), eq(CheckpointStatus.GRADED), any());
        listener.handle("trace.record.sealed", event());
        verify(progress).advance(eq(RENTAL), eq(CHECKPOINT), eq(CheckpointStatus.SEALED), any());
    }

    @Test
    @DisplayName("모르는 필드는 무시하고, 모르는 라우팅 키는 건너뛴다")
    void lenientConsumer() {
        assertThat(listener.handle("trace.diagnostics.recorded", event())).isFalse();
        verifyNoInteractions(progress);
    }

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

    private static String event() {
        return """
                {"schemaVersion":"1.0","eventId":"7uYc0nXq1D2kLm3Np4Qr5","occurredAt":"2026-11-09T14:05:12+09:00",
                 "rentalId":"%s","checkpointId":"%s","grade":"B","somethingNew":true}
                """.formatted(RENTAL, CHECKPOINT);
    }
}
