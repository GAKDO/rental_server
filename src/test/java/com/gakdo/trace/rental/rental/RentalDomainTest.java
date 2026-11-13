package com.gakdo.trace.rental.rental;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.gakdo.trace.rental.common.domain.DomainException;
import com.gakdo.trace.rental.common.domain.ErrorCode;
import com.gakdo.trace.rental.device.domain.Device;
import com.gakdo.trace.rental.device.domain.DeviceChanges;
import com.gakdo.trace.rental.device.domain.DeviceStatus;
import com.gakdo.trace.rental.rental.domain.Checkpoint;
import com.gakdo.trace.rental.rental.domain.CheckpointStatus;
import com.gakdo.trace.rental.rental.domain.CheckpointType;
import com.gakdo.trace.rental.rental.domain.Rental;
import com.gakdo.trace.rental.rental.domain.RentalDetails;
import com.gakdo.trace.rental.rental.domain.RentalStatus;
import com.gakdo.trace.rental.support.Fixtures;

class RentalDomainTest {

    @Test
    @DisplayName("ACTIVE 기기로 만든 렌탈은 OPEN이고 시점이 없다")
    void openRental() {
        Rental rental = Rental.open(Fixtures.device("S1"), "R-2026-0012");
        assertThat(rental.getStatus()).isEqualTo(RentalStatus.OPEN);
        assertThat(rental.getCheckpoints()).isEmpty();
        assertThat(rental.getStartedAt()).isNull();
        assertThat(rental.getPublicId()).hasSize(21);
    }

    @Test
    @DisplayName("수리 중·폐기 기기로 렌탈을 만들면 409 DEVICE_NOT_ACTIVE")
    void inactiveDeviceRejected() {
        for (DeviceStatus status : new DeviceStatus[] {DeviceStatus.MAINTENANCE, DeviceStatus.RETIRED}) {
            Device device = Fixtures.device("S-" + status);
            device.change(new DeviceChanges(null, null, null, status), null);
            assertCode(() -> Rental.open(device, "R"), ErrorCode.DEVICE_NOT_ACTIVE);
        }
    }

    @Test
    @DisplayName("출고 전에 수령 기록을 만들면 거부된다(409 CHECKPOINT_ORDER_VIOLATION)")
    void receiveBeforeOutRejected() {
        Rental rental = Rental.open(Fixtures.device("S1"), "R");
        assertCode(() -> rental.startCheckpoint(CheckpointType.RCV), ErrorCode.CHECKPOINT_ORDER_VIOLATION);
        assertCode(() -> rental.startCheckpoint(CheckpointType.RET), ErrorCode.CHECKPOINT_ORDER_VIOLATION);
    }

    @Test
    @DisplayName("출고가 봉인되기 전에는 수령을 시작할 수 없다")
    void receiveBeforeOutSealedRejected() {
        Rental rental = Rental.open(Fixtures.device("S1"), "R");
        Checkpoint out = rental.startCheckpoint(CheckpointType.OUT);
        out.advanceTo(CheckpointStatus.GRADED, OffsetDateTime.now());
        assertCode(() -> rental.startCheckpoint(CheckpointType.RCV), ErrorCode.CHECKPOINT_ORDER_VIOLATION);
    }

    @Test
    @DisplayName("OUT을 시작하면 startedAt이 채워지고, 같은 시점을 또 시작하면 409")
    void outStartsRental() {
        Rental rental = Rental.open(Fixtures.device("S1"), "R");
        Checkpoint out = rental.startCheckpoint(CheckpointType.OUT);
        assertThat(out.getStatus()).isEqualTo(CheckpointStatus.OPEN);
        assertThat(out.getOccurredAt()).isNull();
        assertThat(rental.getStartedAt()).isNotNull();
        assertCode(() -> rental.startCheckpoint(CheckpointType.OUT), ErrorCode.CHECKPOINT_ORDER_VIOLATION);
    }

    @Test
    @DisplayName("세 시점을 순서대로 봉인하면 RETURNED가 되고 종료하면 CLOSED")
    void fullLifecycle() {
        Rental rental = Rental.open(Fixtures.device("S1"), "R");
        assertCode(rental::close, ErrorCode.RENTAL_NOT_CLOSABLE);
        for (CheckpointType type : CheckpointType.values()) {
            Checkpoint c = rental.startCheckpoint(type);
            seal(rental, c);
        }
        assertThat(rental.getCheckpoints()).extracting(Checkpoint::getType)
                .containsExactly(CheckpointType.OUT, CheckpointType.RCV, CheckpointType.RET);
        assertThat(rental.getStatus()).isEqualTo(RentalStatus.RETURNED);
        assertCode(() -> rental.startCheckpoint(CheckpointType.RET), ErrorCode.CHECKPOINT_ORDER_VIOLATION);

        rental.close();
        assertThat(rental.getStatus()).isEqualTo(RentalStatus.CLOSED);
        assertThat(rental.getEndedAt()).isNotNull();
        assertCode(rental::close, ErrorCode.RENTAL_NOT_CLOSABLE);
    }

    @Test
    @DisplayName("시점 상태는 앞으로만 가고 같은 이벤트가 두 번 와도 결과가 같다")
    void checkpointAdvanceIsIdempotent() {
        Rental rental = Rental.open(Fixtures.device("S1"), "R");
        Checkpoint out = rental.startCheckpoint(CheckpointType.OUT);
        OffsetDateTime captured = OffsetDateTime.parse("2026-11-09T14:05:12+09:00");
        assertThat(out.advanceTo(CheckpointStatus.CAPTURED, captured)).isTrue();
        assertThat(out.advanceTo(CheckpointStatus.CAPTURED, captured.plusHours(1))).isFalse();
        assertThat(out.getOccurredAt()).isEqualTo(captured);

        assertThat(out.advanceTo(CheckpointStatus.SEALED, captured.plusMinutes(5))).isTrue();
        assertThat(out.advanceTo(CheckpointStatus.GRADED, captured.plusMinutes(6))).isFalse();
        assertThat(out.getStatus()).isEqualTo(CheckpointStatus.SEALED);
        assertThat(out.getOccurredAt()).isEqualTo(captured);
    }

    @Test
    @DisplayName("촬영 이벤트를 건너뛰고 봉인이 먼저 와도 occurredAt이 채워진다")
    void sealedWithoutCaptureSetsOccurredAt() {
        Rental rental = Rental.open(Fixtures.device("S1"), "R");
        Checkpoint out = rental.startCheckpoint(CheckpointType.OUT);
        out.advanceTo(CheckpointStatus.SEALED, OffsetDateTime.parse("2026-11-09T14:10:31+09:00"));
        assertThat(out.getOccurredAt()).isNotNull();
        assertThat(CheckpointType.OUT.previous()).isNull();
        assertThat(CheckpointType.RET.previous()).isEqualTo(CheckpointType.RCV);
    }

    @Test
    @DisplayName("지금 시점은 시작 전엔 없고, 출고 봉인 뒤 수령을 시작하면 수령이다")
    void currentCheckpointIsLatestStarted() {
        Rental rental = Rental.open(Fixtures.device("S1"), "R");
        assertThat(rental.currentCheckpoint()).isEmpty();
        Checkpoint out = rental.startCheckpoint(CheckpointType.OUT);
        assertThat(rental.currentCheckpoint()).containsSame(out);
        seal(rental, out);
        Checkpoint rcv = rental.startCheckpoint(CheckpointType.RCV);
        assertThat(rental.currentCheckpoint()).containsSame(rcv);
    }

    private static void seal(Rental rental, Checkpoint checkpoint) {
        checkpoint.advanceTo(CheckpointStatus.SEALED, OffsetDateTime.now());
        rental.onCheckpointSealed(checkpoint);
    }

    private static void assertCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, ErrorCode code) {
        assertThatThrownBy(call).isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.code()).isEqualTo(code));
    }

    @Test
    @DisplayName("등급 이벤트의 등급을 남기고 다시 오면 마지막 값으로 바꾸며, A~D가 아니면 무시한다")
    void recordGrade() {
        Rental rental = Rental.open(com.gakdo.trace.rental.support.Fixtures.device("G1"), "R");
        Checkpoint out = rental.startCheckpoint(CheckpointType.OUT);
        org.assertj.core.api.Assertions.assertThat(out.recordGrade("B")).isTrue();
        org.assertj.core.api.Assertions.assertThat(out.recordGrade("B")).isFalse();
        org.assertj.core.api.Assertions.assertThat(out.recordGrade("Z")).isFalse();
        org.assertj.core.api.Assertions.assertThat(out.recordGrade(null)).isFalse();
        org.assertj.core.api.Assertions.assertThat(out.recordGrade("A")).isTrue();
        org.assertj.core.api.Assertions.assertThat(out.getGrade()).isEqualTo("A");
    }

    @Test
    @DisplayName("고객 이름·메모는 앞뒤 공백을 지우고 빈 값은 비우며, 고치기에서 생략한 칸은 그대로 둔다")
    void updateDetails() {
        Rental rental = Rental.open(com.gakdo.trace.rental.support.Fixtures.device("G2"), "R");
        rental.updateDetails(RentalDetails.of("  김고객 ", java.time.LocalDate.parse("2026-11-19"), "   "));
        org.assertj.core.api.Assertions.assertThat(rental.getCustomerName()).isEqualTo("김고객");
        org.assertj.core.api.Assertions.assertThat(rental.getNote()).isNull();
        rental.updateDetails(new RentalDetails(null, new RentalDetails.Change<>(null), RentalDetails.Change.text("메모")));
        org.assertj.core.api.Assertions.assertThat(rental.getCustomerName()).isEqualTo("김고객");
        org.assertj.core.api.Assertions.assertThat(rental.getDueAt()).isNull();
        org.assertj.core.api.Assertions.assertThat(rental.getNote()).isEqualTo("메모");
        org.assertj.core.api.Assertions.assertThat(RentalDetails.NONE.isEmpty()).isTrue();
    }

    @Test
    @DisplayName("반납 예정일이 한국 시간 오늘보다 과거면 400 VALIDATION_FAILED, 오늘·미래·비우기·생략·이미 저장된 값 그대로는 통과한다")
    void dueAtNotInPast() {
        java.time.LocalDate today = java.time.LocalDate.parse("2026-11-13");
        assertThatThrownBy(() -> RentalDetails.of(null, java.time.LocalDate.parse("2026-11-06"), null).requireDueNotBefore(today, null))
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).code()).isEqualTo(ErrorCode.VALIDATION_FAILED);
        RentalDetails.of(null, today, null).requireDueNotBefore(today, null);
        RentalDetails.of(null, today.plusDays(1), null).requireDueNotBefore(today, null);
        new RentalDetails(null, new RentalDetails.Change<>(null), null).requireDueNotBefore(today, null);
        new RentalDetails(RentalDetails.Change.text("김고객"), null, null).requireDueNotBefore(today, null);
        RentalDetails.NONE.requireDueNotBefore(today, null);
        java.time.LocalDate stored = java.time.LocalDate.parse("2026-11-06");
        RentalDetails.of(null, stored, null).requireDueNotBefore(today, stored);
        assertThatThrownBy(() -> RentalDetails.of(null, stored.minusDays(1), null).requireDueNotBefore(today, stored))
                .isInstanceOf(DomainException.class);
        assertThat(RentalDetails.today()).isEqualTo(java.time.LocalDate.now(java.time.ZoneId.of("Asia/Seoul")));
    }
}
