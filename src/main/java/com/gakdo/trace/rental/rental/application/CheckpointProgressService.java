package com.gakdo.trace.rental.rental.application;

import java.time.OffsetDateTime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.gakdo.trace.rental.rental.domain.Checkpoint;
import com.gakdo.trace.rental.rental.domain.CheckpointStatus;
import com.gakdo.trace.rental.rental.infra.CheckpointRepository;

/**
 * 다른 서비스의 이벤트로 시점 상태를 앞으로 옮긴다(FR-RNT-02).
 * capture.uploaded → CAPTURED, checkpoint.graded → GRADED, record.sealed → SEALED(반납이면 렌탈 RETURNED).
 */
@Service
public class CheckpointProgressService {

    private static final Logger log = LoggerFactory.getLogger(CheckpointProgressService.class);

    private final CheckpointRepository checkpoints;

    public CheckpointProgressService(CheckpointRepository checkpoints) {
        this.checkpoints = checkpoints;
    }

    /** @return 상태가 바뀌었으면 true. 모르는 시점·다른 렌탈의 시점이면 경고만 남기고 false. */
    @Transactional
    public boolean advance(String rentalId, String checkpointId, CheckpointStatus next, OffsetDateTime occurredAt) {
        Checkpoint checkpoint = checkpoints.lockByPublicId(checkpointId).orElse(null);
        if (checkpoint == null) {
            log.warn("모르는 시점의 이벤트를 건너뜁니다: checkpointId={} status={}", checkpointId, next);
            return false;
        }
        if (!checkpoint.getRental().getPublicId().equals(rentalId)) {
            log.warn("렌탈과 시점이 맞지 않는 이벤트를 건너뜁니다: rentalId={} checkpointId={}", rentalId, checkpointId);
            return false;
        }
        boolean changed = checkpoint.advanceTo(next, occurredAt);
        if (changed && checkpoint.isSealed()) {
            checkpoint.getRental().onCheckpointSealed(checkpoint);
        }
        return changed;
    }
}
