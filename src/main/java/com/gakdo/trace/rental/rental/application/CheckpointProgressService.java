package com.gakdo.trace.rental.rental.application;

import java.time.OffsetDateTime;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.gakdo.trace.rental.rental.domain.Checkpoint;
import com.gakdo.trace.rental.rental.domain.CheckpointStatus;
import com.gakdo.trace.rental.rental.infra.CheckpointRepository;

/**
 * 다른 서비스의 이벤트로 시점 상태를 앞으로 옮긴다(FR-RNT-02).
 * capture.uploaded → CAPTURED, checkpoint.graded → GRADED, record.sealed → SEALED(반납이면 렌탈 RETURNED).
 * 부스·폰이 보낸 rentalId·checkpointId 짝은 capture-server가 대조하지 않으므로 여기서 대조한다.
 * 짝이 어긋나면 아무것도 바꾸지 않고 결과로 알린다(리스너가 DLQ로 보냄).
 */
@Service
public class CheckpointProgressService {

    private final CheckpointRepository checkpoints;

    public CheckpointProgressService(CheckpointRepository checkpoints) {
        this.checkpoints = checkpoints;
    }

    public CheckpointAdvance advance(String rentalId, String checkpointId, CheckpointStatus next, OffsetDateTime occurredAt) {
        return advance(rentalId, checkpointId, next, occurredAt, null);
    }

    /**
     * @param grade trace.checkpoint.graded의 등급(A~D). 다른 이벤트면 null. 상태가 이미 앞서 있어도 등급은 마지막 값으로 남긴다(11/10)
     */
    @Transactional
    public CheckpointAdvance advance(String rentalId, String checkpointId, CheckpointStatus next, OffsetDateTime occurredAt,
            String grade) {
        Checkpoint checkpoint = checkpoints.lockByPublicId(checkpointId).orElse(null);
        if (checkpoint == null) {
            return CheckpointAdvance.UNKNOWN_CHECKPOINT;
        }
        if (!checkpoint.getRental().getPublicId().equals(rentalId)) {
            return CheckpointAdvance.RENTAL_MISMATCH;
        }
        boolean changed = checkpoint.advanceTo(next, occurredAt);
        changed |= checkpoint.recordGrade(grade);
        if (changed && checkpoint.isSealed()) {
            checkpoint.getRental().onCheckpointSealed(checkpoint);
        }
        return changed ? CheckpointAdvance.CHANGED : CheckpointAdvance.UNCHANGED;
    }
}
