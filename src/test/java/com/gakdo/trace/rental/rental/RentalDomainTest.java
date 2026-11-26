// 이 파일이 확인하는 것: 렌탈 한 건의 규칙(서버·DB 없이). 시점은 출고 → 수령 → 반납 순서로만 진행하고,
// 앞 시점이 봉인돼야 다음 시점을 시작한다. 세 시점이 봉인되면 반납 완료, 관리자가 종료하면 끝.

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

    // [상황] 사용 가능 기기로 렌탈을 만든다.
    // [기대] 진행 중(OPEN), 시점은 아직 없음.
    // (도구) @Test = JUnit에게 "이 메서드가 테스트 하나"라고 알리는 표시. @DisplayName = 결과 화면에 보일 한글 이름
    @Test
    @DisplayName("ACTIVE 기기로 만든 렌탈은 OPEN이고 시점이 없다")
    void openRental() {
        // 준비+실행: 테스트용 기기(시리얼 S1)로 렌탈 한 건을 연다 (Fixtures = DB 없이 쓰는 견본 기기를 만들어 주는 도우미)
        Rental rental = Rental.open(Fixtures.device("S1"), "R-2026-0012");
        // (도구) assertThat(실제값).isEqualTo(기대값) = "실제값이 기대값과 같아야 한다"는 검사. 다르면 테스트 실패
        // 확인: 갓 만든 렌탈의 상태는 진행 중(OPEN)이어야 한다
        assertThat(rental.getStatus()).isEqualTo(RentalStatus.OPEN);
        // 확인: 아직 출고·수령·반납 시점을 하나도 시작하지 않았으니 시점 목록은 비어 있어야 한다
        assertThat(rental.getCheckpoints()).isEmpty();
        // 확인: 렌탈 시작 시각은 출고를 시작할 때 채워지므로 지금은 비어(null) 있어야 한다
        assertThat(rental.getStartedAt()).isNull();
        // 확인: 외부에 보이는 렌탈 ID(publicId)는 21자 NanoID로 만들어져야 한다 (팀 ID 규칙)
        assertThat(rental.getPublicId()).hasSize(21);
    }

    // [상황] 수리 중·폐기 기기로 렌탈을 만든다.
    // [기대] 409 DEVICE_NOT_ACTIVE.
    //        ※ 응답 코드: 409 = 지금 상태와 맞지 않아 거절(이미 있음·순서 어긋남 등)
    @Test
    @DisplayName("수리 중·폐기 기기로 렌탈을 만들면 409 DEVICE_NOT_ACTIVE")
    void inactiveDeviceRejected() {
        // 준비: 수리 중(MAINTENANCE)과 폐기(RETIRED) 두 상태를 하나씩 돌며 같은 검사를 반복한다
        for (DeviceStatus status : new DeviceStatus[] {DeviceStatus.MAINTENANCE, DeviceStatus.RETIRED}) {
            // 준비: 상태마다 다른 시리얼의 테스트용 기기를 만든다 (처음엔 사용 가능 상태)
            Device device = Fixtures.device("S-" + status);
            // 준비: 기기 상태만 이번 차례의 상태(수리 중/폐기)로 바꾼다 (다른 칸은 null = 그대로)
            device.change(new DeviceChanges(null, null, null, status), null);
            // 실행+확인: 이 기기로 렌탈을 열면 DEVICE_NOT_ACTIVE 에러가 나야 한다 (쓸 수 없는 기기 대여 방지)
            assertCode(() -> Rental.open(device, "R"), ErrorCode.DEVICE_NOT_ACTIVE);
        }
    }

    // [상황] 출고 시점 없이 수령 시점을 만든다.
    // [기대] 409 CHECKPOINT_ORDER_VIOLATION.
    //        ※ 응답 코드: 409 = 지금 상태와 맞지 않아 거절(이미 있음·순서 어긋남 등)
    @Test
    @DisplayName("출고 전에 수령 기록을 만들면 거부된다(409 CHECKPOINT_ORDER_VIOLATION)")
    void receiveBeforeOutRejected() {
        // 준비: 사용 가능한 테스트용 기기(시리얼 S1)로 렌탈 한 건을 만든다
        Rental rental = Rental.open(Fixtures.device("S1"), "R");
        // 실행+확인: 출고 없이 수령을 시작하면 순서 위반 에러가 나야 한다 (순서 건너뛰기 방지)
        assertCode(() -> rental.startCheckpoint(CheckpointType.RCV), ErrorCode.CHECKPOINT_ORDER_VIOLATION);
        // 실행+확인: 출고 없이 반납을 시작해도 같은 순서 위반 에러가 나야 한다
        assertCode(() -> rental.startCheckpoint(CheckpointType.RET), ErrorCode.CHECKPOINT_ORDER_VIOLATION);
    }

    // [상황] 출고를 시작만 하고 봉인 전에 수령을 시작한다.
    // [기대] 거부된다.
    @Test
    @DisplayName("출고가 봉인되기 전에는 수령을 시작할 수 없다")
    void receiveBeforeOutSealedRejected() {
        // 준비: 테스트용 기기로 렌탈 한 건을 만든다
        Rental rental = Rental.open(Fixtures.device("S1"), "R");
        // 준비: 출고(OUT) 시점을 시작한다
        Checkpoint out = rental.startCheckpoint(CheckpointType.OUT);
        // 준비: 출고를 등급 매김(GRADED)까지만 진행한다. 봉인(SEALED) 바로 앞 단계라 아직 봉인은 아니다
        out.advanceTo(CheckpointStatus.GRADED, OffsetDateTime.now());
        // 실행+확인: 출고가 봉인되지 않았으니 수령을 시작하면 순서 위반 에러가 나야 한다
        assertCode(() -> rental.startCheckpoint(CheckpointType.RCV), ErrorCode.CHECKPOINT_ORDER_VIOLATION);
    }

    // [상황] 출고를 시작하고, 같은 출고를 또 시작한다.
    // [기대] 렌탈 시작 시각이 채워지고, 두 번째는 409.
    //        ※ 응답 코드: 409 = 지금 상태와 맞지 않아 거절(이미 있음·순서 어긋남 등)
    @Test
    @DisplayName("OUT을 시작하면 startedAt이 채워지고, 같은 시점을 또 시작하면 409")
    void outStartsRental() {
        // 준비: 테스트용 기기로 렌탈 한 건을 만든다
        Rental rental = Rental.open(Fixtures.device("S1"), "R");
        // 실행: 출고(OUT) 시점을 시작한다
        Checkpoint out = rental.startCheckpoint(CheckpointType.OUT);
        // 확인: 막 시작한 출고 시점의 상태는 열림(OPEN)이어야 한다
        assertThat(out.getStatus()).isEqualTo(CheckpointStatus.OPEN);
        // 확인: 아직 촬영 전이니 시점 발생 시각(occurredAt)은 비어 있어야 한다
        assertThat(out.getOccurredAt()).isNull();
        // 확인: 출고를 시작했으니 렌탈 시작 시각이 채워져 있어야 한다
        assertThat(rental.getStartedAt()).isNotNull();
        // 실행+확인: 같은 출고를 또 시작하면 순서 위반 에러가 나야 한다 (같은 시점 중복 방지)
        assertCode(() -> rental.startCheckpoint(CheckpointType.OUT), ErrorCode.CHECKPOINT_ORDER_VIOLATION);
    }

    // [상황] 출고·수령·반납을 차례로 봉인하고 렌탈을 종료한다.
    // [기대] 반납 완료(RETURNED)가 되고, 종료하면 CLOSED.
    @Test
    @DisplayName("세 시점을 순서대로 봉인하면 RETURNED가 되고 종료하면 CLOSED")
    void fullLifecycle() {
        // 준비: 테스트용 기기로 렌탈 한 건을 만든다
        Rental rental = Rental.open(Fixtures.device("S1"), "R");
        // 실행+확인: 반납 전에 종료하려 하면 RENTAL_NOT_CLOSABLE 에러가 나야 한다 (rental::close = "close를 불러라"를 짧게 쓴 것)
        assertCode(rental::close, ErrorCode.RENTAL_NOT_CLOSABLE);
        // 실행: 출고 → 수령 → 반납 세 종류를 순서대로 하나씩 돈다
        for (CheckpointType type : CheckpointType.values()) {
            // 실행: 이번 차례 시점을 시작한다
            Checkpoint c = rental.startCheckpoint(type);
            // 실행: 시작한 시점을 바로 봉인하고 렌탈에도 알린다 (아래 seal 도우미 메서드)
            seal(rental, c);
        }
        // (도구) extracting(Checkpoint::getType) = 목록의 각 항목에서 종류만 뽑아 비교. containsExactly = 이 순서 그대로 들어 있어야 함
        // 확인: 시점 목록이 출고·수령·반납 순서로 정확히 세 개여야 한다
        assertThat(rental.getCheckpoints()).extracting(Checkpoint::getType)
                .containsExactly(CheckpointType.OUT, CheckpointType.RCV, CheckpointType.RET);
        // 확인: 반납까지 봉인됐으니 렌탈 상태는 반납 완료(RETURNED)여야 한다
        assertThat(rental.getStatus()).isEqualTo(RentalStatus.RETURNED);
        // 실행+확인: 반납 완료 뒤에 반납을 다시 시작하면 순서 위반 에러가 나야 한다
        assertCode(() -> rental.startCheckpoint(CheckpointType.RET), ErrorCode.CHECKPOINT_ORDER_VIOLATION);

        // 실행: 관리자가 렌탈을 종료한다
        rental.close();
        // 확인: 종료 후 상태는 종료(CLOSED)여야 한다
        assertThat(rental.getStatus()).isEqualTo(RentalStatus.CLOSED);
        // 확인: 종료 시각(endedAt)이 채워져 있어야 한다
        assertThat(rental.getEndedAt()).isNotNull();
        // 실행+확인: 이미 종료된 렌탈을 또 종료하면 RENTAL_NOT_CLOSABLE 에러가 나야 한다
        assertCode(rental::close, ErrorCode.RENTAL_NOT_CLOSABLE);
    }

    // [상황] 시점 상태 이벤트가 두 번 오거나 뒤 단계가 먼저 온다.
    // [기대] 상태는 앞으로만 가고, 같은 이벤트가 두 번 와도 결과가 같다.
    @Test
    @DisplayName("시점 상태는 앞으로만 가고 같은 이벤트가 두 번 와도 결과가 같다")
    void checkpointAdvanceIsIdempotent() {
        // 준비: 테스트용 기기로 렌탈 한 건을 만든다
        Rental rental = Rental.open(Fixtures.device("S1"), "R");
        // 준비: 출고 시점을 시작한다
        Checkpoint out = rental.startCheckpoint(CheckpointType.OUT);
        // 준비: 촬영 시각으로 쓸 고정 시각(한국 시간 11/9 14:05:12)을 만든다
        OffsetDateTime captured = OffsetDateTime.parse("2026-11-09T14:05:12+09:00");
        // 실행+확인: 촬영됨(CAPTURED)으로 처음 올리면 실제로 바뀌었다는 뜻으로 true를 돌려줘야 한다
        assertThat(out.advanceTo(CheckpointStatus.CAPTURED, captured)).isTrue();
        // 실행+확인: 같은 촬영됨 이벤트가 한 시간 뒤 또 와도 바뀐 게 없으니 false여야 한다 (중복 이벤트 무시)
        assertThat(out.advanceTo(CheckpointStatus.CAPTURED, captured.plusHours(1))).isFalse();
        // 확인: 발생 시각은 처음 받은 촬영 시각 그대로여야 한다 (두 번째 이벤트가 덮어쓰지 않음)
        assertThat(out.getOccurredAt()).isEqualTo(captured);

        // 실행+확인: 5분 뒤 봉인됨(SEALED)으로 올리면 앞으로 가는 것이니 true여야 한다
        assertThat(out.advanceTo(CheckpointStatus.SEALED, captured.plusMinutes(5))).isTrue();
        // 실행+확인: 봉인 뒤에 늦게 온 등급 매김(GRADED, 봉인보다 앞 단계)은 뒤로 가는 것이니 false여야 한다
        assertThat(out.advanceTo(CheckpointStatus.GRADED, captured.plusMinutes(6))).isFalse();
        // 확인: 상태는 봉인(SEALED) 그대로여야 한다 (뒤로 돌아가지 않음)
        assertThat(out.getStatus()).isEqualTo(CheckpointStatus.SEALED);
        // 확인: 발생 시각도 처음 촬영 시각 그대로여야 한다
        assertThat(out.getOccurredAt()).isEqualTo(captured);
    }

    // [상황] "촬영됨" 없이 "봉인됨"이 먼저 온다.
    // [기대] 시점 발생 시각이 채워진다.
    @Test
    @DisplayName("촬영 이벤트를 건너뛰고 봉인이 먼저 와도 occurredAt이 채워진다")
    void sealedWithoutCaptureSetsOccurredAt() {
        // 준비: 테스트용 기기로 렌탈 한 건을 만든다
        Rental rental = Rental.open(Fixtures.device("S1"), "R");
        // 준비: 출고 시점을 시작한다
        Checkpoint out = rental.startCheckpoint(CheckpointType.OUT);
        // 실행: 촬영됨 이벤트 없이 봉인됨 이벤트가 먼저 온 상황을 만든다
        out.advanceTo(CheckpointStatus.SEALED, OffsetDateTime.parse("2026-11-09T14:10:31+09:00"));
        // 확인: 촬영 이벤트를 놓쳤어도 발생 시각이 채워져 있어야 한다 (봉인 시각으로 대신 채움)
        assertThat(out.getOccurredAt()).isNotNull();
        // 확인: 출고는 첫 시점이라 앞 시점이 없어야(null) 한다
        assertThat(CheckpointType.OUT.previous()).isNull();
        // 확인: 반납의 앞 시점은 수령이어야 한다 (순서 규칙의 기준값)
        assertThat(CheckpointType.RET.previous()).isEqualTo(CheckpointType.RCV);
    }

    // [상황] 시점을 하나씩 시작하며 "지금 시점"을 본다.
    // [기대] 시작 전에는 없고, 출고 봉인 뒤 수령을 시작하면 수령이다.
    @Test
    @DisplayName("지금 시점은 시작 전엔 없고, 출고 봉인 뒤 수령을 시작하면 수령이다")
    void currentCheckpointIsLatestStarted() {
        // 준비: 테스트용 기기로 렌탈 한 건을 만든다
        Rental rental = Rental.open(Fixtures.device("S1"), "R");
        // 확인: 아무 시점도 시작하지 않았으니 지금 시점은 비어 있어야 한다
        assertThat(rental.currentCheckpoint()).isEmpty();
        // 실행: 출고 시점을 시작한다
        Checkpoint out = rental.startCheckpoint(CheckpointType.OUT);
        // 확인: 지금 시점은 방금 시작한 출고 객체 그 자체여야 한다 (containsSame = 같은 객체인지 비교)
        assertThat(rental.currentCheckpoint()).containsSame(out);
        // 실행: 출고를 봉인한다
        seal(rental, out);
        // 실행: 출고가 봉인됐으니 수령 시점을 시작한다
        Checkpoint rcv = rental.startCheckpoint(CheckpointType.RCV);
        // 확인: 지금 시점은 가장 뒤에 시작한 수령이어야 한다
        assertThat(rental.currentCheckpoint()).containsSame(rcv);
    }

    // 도우미: 시점을 봉인 상태로 만들고 렌탈에 "봉인됐다"고 알린다 (여러 테스트에서 반복되는 두 줄을 묶음)
    private static void seal(Rental rental, Checkpoint checkpoint) {
        // 시점 상태를 봉인(SEALED)으로 올린다
        checkpoint.advanceTo(CheckpointStatus.SEALED, OffsetDateTime.now());
        // 렌탈에 봉인을 알린다. 반납이 봉인되면 렌탈이 반납 완료(RETURNED)로 바뀐다
        rental.onCheckpointSealed(checkpoint);
    }

    // 도우미: "이 코드를 실행하면 이 에러 코드가 나야 한다"를 한 줄로 쓰게 해 준다
    private static void assertCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, ErrorCode code) {
        // (도구) assertThatThrownBy(코드) = 그 코드를 실행했을 때 예외가 나야 한다는 검사. isInstanceOfSatisfying = 예외 종류와 내용까지 확인
        // 실행+확인: call을 실행해 DomainException이 나고, 그 에러 코드가 기대한 code와 같아야 한다
        assertThatThrownBy(call).isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.code()).isEqualTo(code));
    }

    // [상황] 등급 이벤트가 B로 오고, 다시 C로 오고, 이상한 값(E)으로 온다.
    // [기대] 마지막 정상 값(C)을 남기고, A~D가 아닌 값은 무시한다.
    @Test
    @DisplayName("등급 이벤트의 등급을 남기고 다시 오면 마지막 값으로 바꾸며, A~D가 아니면 무시한다")
    void recordGrade() {
        // 준비: 테스트용 기기(시리얼 G1)로 렌탈을 만든다
        Rental rental = Rental.open(com.gakdo.trace.rental.support.Fixtures.device("G1"), "R");
        // 준비: 출고 시점을 시작한다 (등급은 시점마다 붙는다)
        Checkpoint out = rental.startCheckpoint(CheckpointType.OUT);
        // 실행+확인: 처음 B 등급이 오면 기록되고 true여야 한다
        org.assertj.core.api.Assertions.assertThat(out.recordGrade("B")).isTrue();
        // 실행+확인: 같은 B가 또 오면 바뀐 게 없으니 false여야 한다 (중복 이벤트 무시)
        org.assertj.core.api.Assertions.assertThat(out.recordGrade("B")).isFalse();
        // 실행+확인: A~D가 아닌 이상한 값(Z)은 무시하고 false여야 한다
        org.assertj.core.api.Assertions.assertThat(out.recordGrade("Z")).isFalse();
        // 실행+확인: 값이 없는(null) 등급도 무시하고 false여야 한다
        org.assertj.core.api.Assertions.assertThat(out.recordGrade(null)).isFalse();
        // 실행+확인: 정상 값 A가 새로 오면 바뀌고 true여야 한다
        org.assertj.core.api.Assertions.assertThat(out.recordGrade("A")).isTrue();
        // 확인: 최종 등급은 마지막 정상 값 A여야 한다
        org.assertj.core.api.Assertions.assertThat(out.getGrade()).isEqualTo("A");
    }

    // [상황] 고객 이름·메모를 앞뒤 공백, 빈 값으로 넣고 일부 칸만 고친다.
    // [기대] 공백은 지우고 빈 값은 비우며, 고치지 않은 칸은 그대로.
    @Test
    @DisplayName("고객 이름·메모는 앞뒤 공백을 지우고 빈 값은 비우며, 고치기에서 생략한 칸은 그대로 둔다")
    void updateDetails() {
        // 준비: 테스트용 기기(시리얼 G2)로 렌탈을 만든다
        Rental rental = Rental.open(com.gakdo.trace.rental.support.Fixtures.device("G2"), "R");
        // 실행: 이름은 앞뒤 공백 붙은 "김고객", 반납 예정일 11/19, 메모는 공백만 넣어 고친다
        rental.updateDetails(RentalDetails.of("  김고객 ", java.time.LocalDate.parse("2026-11-19"), "   "));
        // 확인: 이름의 앞뒤 공백이 지워져 "김고객"만 남아야 한다
        org.assertj.core.api.Assertions.assertThat(rental.getCustomerName()).isEqualTo("김고객");
        // 확인: 공백뿐인 메모는 빈 값으로 보고 비워져(null) 있어야 한다
        org.assertj.core.api.Assertions.assertThat(rental.getNote()).isNull();
        // 실행: 이름은 생략(null = 그대로), 반납 예정일은 비우기(Change 안의 값 null), 메모는 "메모"로 고친다
        rental.updateDetails(new RentalDetails(null, new RentalDetails.Change<>(null), RentalDetails.Change.text("메모")));
        // 확인: 생략한 이름은 이전 값 "김고객" 그대로여야 한다
        org.assertj.core.api.Assertions.assertThat(rental.getCustomerName()).isEqualTo("김고객");
        // 확인: 비우기로 보낸 반납 예정일은 비워져 있어야 한다
        org.assertj.core.api.Assertions.assertThat(rental.getDueAt()).isNull();
        // 확인: 메모는 새 값 "메모"여야 한다
        org.assertj.core.api.Assertions.assertThat(rental.getNote()).isEqualTo("메모");
        // 확인: 아무 칸도 고치지 않는 NONE은 "비어 있음"으로 판단돼야 한다
        org.assertj.core.api.Assertions.assertThat(RentalDetails.NONE.isEmpty()).isTrue();
    }

    // [상황] 반납 예정일을 어제, 오늘, 내일, 비우기, 생략, 이미 저장된 과거 값 그대로로 넣는다.
    // [기대] 한국 시간 오늘보다 과거면 400, 나머지는 통과(이미 저장된 값을 그대로 두는 건 허용).
    //        ※ 응답 코드: 400 = 요청 내용이 형식에 맞지 않음
    @Test
    @DisplayName("반납 예정일이 한국 시간 오늘보다 과거면 400 VALIDATION_FAILED, 오늘·미래·비우기·생략·이미 저장된 값 그대로는 통과한다")
    void dueAtNotInPast() {
        // 준비: "오늘"을 11/13로 고정한다 (실제 날짜에 따라 결과가 바뀌지 않게)
        java.time.LocalDate today = java.time.LocalDate.parse("2026-11-13");
        // 준비: 오늘보다 과거인 11/6을 반납 예정일로 넣는 변경을 만든다
        RentalDetails pastDue = RentalDetails.of(null, java.time.LocalDate.parse("2026-11-06"), null);
        // 실행+확인: 과거 날짜를 검사하면 예외가 나야 한다
        assertThatThrownBy(() -> pastDue.requireDueNotBefore(today, null))
                // 확인: 예외 종류는 DomainException이어야 한다
                .isInstanceOf(DomainException.class)
                // 확인: 예외에서 에러 코드만 꺼내 보면 VALIDATION_FAILED(400)여야 한다
                .extracting(e -> ((DomainException) e).code()).isEqualTo(ErrorCode.VALIDATION_FAILED);
        // 실행+확인: 오늘 날짜는 예외 없이 통과해야 한다 (예외가 나면 테스트가 실패)
        RentalDetails.of(null, today, null).requireDueNotBefore(today, null);
        // 실행+확인: 내일 날짜도 예외 없이 통과해야 한다
        RentalDetails.of(null, today.plusDays(1), null).requireDueNotBefore(today, null);
        // 실행+확인: 반납 예정일 비우기(값 null)는 검사하지 않고 통과해야 한다
        new RentalDetails(null, new RentalDetails.Change<>(null), null).requireDueNotBefore(today, null);
        // 실행+확인: 반납 예정일을 생략하고 이름만 고칠 때도 통과해야 한다
        new RentalDetails(RentalDetails.Change.text("김고객"), null, null).requireDueNotBefore(today, null);
        // 실행+확인: 아무것도 고치지 않는 NONE도 통과해야 한다
        RentalDetails.NONE.requireDueNotBefore(today, null);
        // 준비: DB에 이미 저장돼 있던 과거 반납 예정일 11/6을 만든다
        java.time.LocalDate stored = java.time.LocalDate.parse("2026-11-06");
        // 실행+확인: 저장된 과거 값과 같은 값을 그대로 보내면 통과해야 한다 (다른 칸만 고칠 때 막히지 않게)
        RentalDetails.of(null, stored, null).requireDueNotBefore(today, stored);
        // 준비: 저장된 값보다 하루 더 과거인 날짜로 바꾸는 변경을 만든다
        RentalDetails earlierThanStored = RentalDetails.of(null, stored.minusDays(1), null);
        // 실행+확인: 저장된 값과 다른 과거 날짜는 예외가 나야 한다
        assertThatThrownBy(() -> earlierThanStored.requireDueNotBefore(today, stored))
                // 확인: 예외 종류는 DomainException이어야 한다
                .isInstanceOf(DomainException.class);
        // 확인: RentalDetails.today()는 한국 시간(Asia/Seoul) 기준 오늘 날짜를 돌려줘야 한다
        assertThat(RentalDetails.today()).isEqualTo(java.time.LocalDate.now(java.time.ZoneId.of("Asia/Seoul")));
    }
}
