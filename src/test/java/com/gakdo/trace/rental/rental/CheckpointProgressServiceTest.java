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

    private static final OffsetDateTime AT = OffsetDateTime.parse("2026-11-09T14:05:12+09:00");

    private final CheckpointRepository checkpoints = mock(CheckpointRepository.class);
    private final CheckpointProgressService service = new CheckpointProgressService(checkpoints);

    @Test
    @DisplayName("렌탈·시점 짝이 맞으면 시점 상태를 옮기고, 같은 이벤트가 다시 오면 바뀐 것이 없다")
    void matchingPairAdvances() {
        Rental rental = Rental.open(Fixtures.device("S1"), "R");
        Checkpoint out = rental.startCheckpoint(CheckpointType.OUT);
        when(checkpoints.lockByPublicId(out.getPublicId())).thenReturn(Optional.of(out));

        assertThat(service.advance(rental.getPublicId(), out.getPublicId(), CheckpointStatus.CAPTURED, AT, null))
                .isEqualTo(CheckpointAdvance.CHANGED);
        assertThat(out.getStatus()).isEqualTo(CheckpointStatus.CAPTURED);
        assertThat(service.advance(rental.getPublicId(), out.getPublicId(), CheckpointStatus.CAPTURED, AT, null))
                .isEqualTo(CheckpointAdvance.UNCHANGED);
    }

    @Test
    @DisplayName("다른 렌탈의 시점으로 온 이벤트는 시점을 바꾸지 않고 짝 불일치로 알린다")
    void otherRentalsCheckpointIsMismatch() {
        Rental mine = Rental.open(Fixtures.device("S1"), "R1");
        Rental other = Rental.open(Fixtures.device("S2"), "R2");
        Checkpoint out = mine.startCheckpoint(CheckpointType.OUT);
        when(checkpoints.lockByPublicId(out.getPublicId())).thenReturn(Optional.of(out));

        assertThat(service.advance(other.getPublicId(), out.getPublicId(), CheckpointStatus.CAPTURED, AT, null))
                .isEqualTo(CheckpointAdvance.RENTAL_MISMATCH);
        assertThat(out.getStatus()).isNotEqualTo(CheckpointStatus.CAPTURED);
    }

    @Test
    @DisplayName("rental-server에 없는 시점으로 온 이벤트는 모르는 시점으로 알린다")
    void unknownCheckpoint() {
        when(checkpoints.lockByPublicId("V1StGXR8_Z5jdHi6B-myT")).thenReturn(Optional.empty());
        assertThat(service.advance("IPv6mcQOlTkKj6mOecAQt", "V1StGXR8_Z5jdHi6B-myT", CheckpointStatus.CAPTURED, AT, null))
                .isEqualTo(CheckpointAdvance.UNKNOWN_CHECKPOINT);
    }
}
