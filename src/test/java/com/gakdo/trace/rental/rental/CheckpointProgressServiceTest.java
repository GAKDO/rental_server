// 이 파일이 확인하는 것: 이벤트가 가리키는 렌탈·시점이 맞는지 확인하고 시점 상태를 옮기는 규칙.

package com.gakdo.trace.rental.rental;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.gakdo.trace.rental.rental.application.CheckpointAdvance;
import com.gakdo.trace.rental.rental.application.CheckpointProgressService;
import com.gakdo.trace.rental.rental.domain.Checkpoint;
import com.gakdo.trace.rental.rental.domain.CheckpointStatus;
import com.gakdo.trace.rental.rental.domain.CheckpointType;
import com.gakdo.trace.rental.rental.domain.Rental;
import com.gakdo.trace.rental.rental.infra.CheckpointRepository;
import com.gakdo.trace.rental.support.Fixtures;

class CheckpointProgressServiceTest {

    // 준비: 이벤트가 알려 주는 발생 시각으로 쓸 고정 시각(한국 시간 11/9 14:05:12)
    private static final OffsetDateTime AT = OffsetDateTime.parse("2026-11-09T14:05:12+09:00");

    // (도구) mock(클래스) = 진짜 DB 대신 쓰는 가짜 객체(목)를 만든다. 시키지 않은 질문엔 빈 값을 돌려준다
    // 준비: 시점 저장소를 가짜로 만든다 (DB 없이 테스트하려고)
    private final CheckpointRepository checkpoints = mock(CheckpointRepository.class);
    // 준비: 시험할 대상인 시점 진행 서비스를 가짜 저장소로 만든다
    private final CheckpointProgressService service = new CheckpointProgressService(checkpoints);

    // [상황] 맞는 렌탈·시점으로 "촬영됨"이 오고, 같은 것이 또 온다.
    // [기대] 처음엔 바뀜, 두 번째는 바뀐 것 없음.
    // (도구) @Test = JUnit에게 "이 메서드가 테스트 하나"라고 알리는 표시. @DisplayName = 결과 화면에 보일 한글 이름
    @Test
    @DisplayName("렌탈·시점 짝이 맞으면 시점 상태를 옮기고, 같은 이벤트가 다시 오면 바뀐 것이 없다")
    void matchingPairAdvances() {
        // 준비: 테스트 기기(S1)로 렌탈 한 건을 만든다
        Rental rental = Rental.open(Fixtures.device("S1"), "R");
        // 준비: 그 렌탈의 출고 시점을 시작한다
        Checkpoint out = rental.startCheckpoint(CheckpointType.OUT);
        // (도구) when(가짜.메서드(값)).thenReturn(결과) = "가짜에게 이 질문이 오면 이 결과를 돌려줘"라고 미리 정해 두는 것
        // 준비: 저장소가 이 시점 ID로 (잠그고) 찾으면 위 출고 시점을 돌려주게 한다
        when(checkpoints.lockByPublicId(out.getPublicId())).thenReturn(Optional.of(out));

        // (도구) assertThat(실제값).isEqualTo(기대값) = "실제값이 기대값과 같아야 한다"는 검사. 다르면 테스트 실패
        // 실행: 맞는 렌탈 ID·시점 ID로 "촬영됨(CAPTURED)" 이벤트를 처리한다 (마지막 null = 등급 없음)
        assertThat(service.advance(rental.getPublicId(), out.getPublicId(), CheckpointStatus.CAPTURED, AT, null))
                // 확인: 처음 온 이벤트라 결과는 "바뀜(CHANGED)"이어야 한다
                .isEqualTo(CheckpointAdvance.CHANGED);
        // 확인: 시점 상태가 실제로 촬영됨(CAPTURED)으로 옮겨져 있어야 한다
        assertThat(out.getStatus()).isEqualTo(CheckpointStatus.CAPTURED);
        // 실행: 같은 이벤트를 한 번 더 처리한다 (메시지가 중복 배달된 상황)
        assertThat(service.advance(rental.getPublicId(), out.getPublicId(), CheckpointStatus.CAPTURED, AT, null))
                // 확인: 이미 반영된 내용이라 결과는 "바뀐 것 없음(UNCHANGED)"이어야 한다
                .isEqualTo(CheckpointAdvance.UNCHANGED);
    }

    // [상황] 다른 렌탈 번호로 내 렌탈의 시점을 바꾸려는 이벤트가 온다.
    // [기대] 바꾸지 않고 "짝 불일치"로 알린다.
    @Test
    @DisplayName("다른 렌탈의 시점으로 온 이벤트는 시점을 바꾸지 않고 짝 불일치로 알린다")
    void otherRentalsCheckpointIsMismatch() {
        // 준비: 내 렌탈(기기 S1)을 만든다
        Rental mine = Rental.open(Fixtures.device("S1"), "R1");
        // 준비: 다른 사람 렌탈(기기 S2)을 만든다
        Rental other = Rental.open(Fixtures.device("S2"), "R2");
        // 준비: 내 렌탈의 출고 시점을 시작한다
        Checkpoint out = mine.startCheckpoint(CheckpointType.OUT);
        // 준비: 저장소가 이 시점 ID로 찾으면 내 출고 시점을 돌려주게 한다
        when(checkpoints.lockByPublicId(out.getPublicId())).thenReturn(Optional.of(out));

        // 실행: 다른 렌탈 ID + 내 시점 ID로 된 (짝이 안 맞는) 촬영됨 이벤트를 처리한다
        assertThat(service.advance(other.getPublicId(), out.getPublicId(), CheckpointStatus.CAPTURED, AT, null))
                // 확인: 결과는 "렌탈·시점 짝 불일치(RENTAL_MISMATCH)"여야 한다
                .isEqualTo(CheckpointAdvance.RENTAL_MISMATCH);
        // 확인: 내 시점 상태는 촬영됨으로 바뀌지 않았어야 한다 (남의 이벤트로 내 기록이 바뀌는 것 방지)
        assertThat(out.getStatus()).isNotEqualTo(CheckpointStatus.CAPTURED);
    }

    // [상황] 이 서버에 없는 시점으로 이벤트가 온다.
    // [기대] "모르는 시점"으로 알린다.
    @Test
    @DisplayName("rental-server에 없는 시점으로 온 이벤트는 모르는 시점으로 알린다")
    void unknownCheckpoint() {
        // 준비: 저장소가 이 시점 ID로 찾으면 "없음(Optional.empty)"을 돌려주게 한다
        when(checkpoints.lockByPublicId("V1StGXR8_Z5jdHi6B-myT")).thenReturn(Optional.empty());
        // 실행: 이 서버에 없는 시점 ID로 촬영됨 이벤트를 처리한다
        assertThat(service.advance("IPv6mcQOlTkKj6mOecAQt", "V1StGXR8_Z5jdHi6B-myT", CheckpointStatus.CAPTURED, AT, null))
                // 확인: 결과는 "모르는 시점(UNKNOWN_CHECKPOINT)"이어야 한다
                .isEqualTo(CheckpointAdvance.UNKNOWN_CHECKPOINT);
    }
}
