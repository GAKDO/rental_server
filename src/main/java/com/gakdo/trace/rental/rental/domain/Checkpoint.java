package com.gakdo.trace.rental.rental.domain;

import java.time.OffsetDateTime;

import com.gakdo.trace.rental.common.infra.Times;
import com.gakdo.trace.rental.common.infra.PublicIds;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/** 시점(rental.checkpoint). 모든 기록의 기준점이고 다른 서비스는 이 ID를 값으로만 든다. */
@Entity
@Table(name = "checkpoint")
public class Checkpoint {

    /** DB 기본 키(순번). 밖에 내보내지 않는다. */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 밖에 보이는 ID(NanoID 21자) */
    @Column(name = "public_id", nullable = false, updatable = false, length = 21)
    private String publicId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "rental_id", nullable = false, updatable = false)
    private Rental rental;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 3)
    private CheckpointType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private CheckpointStatus status;

    /** 촬영이 끝난 시각(trace.capture.uploaded). 아직이면 null. */
    @Column(name = "occurred_at")
    private OffsetDateTime occurredAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    protected Checkpoint() {
    }

    static Checkpoint open(Rental rental, CheckpointType type) {
        Checkpoint checkpoint = new Checkpoint();
        checkpoint.publicId = PublicIds.next();
        checkpoint.rental = rental;
        checkpoint.type = type;
        checkpoint.status = CheckpointStatus.OPEN;
        checkpoint.createdAt = Times.now();
        checkpoint.updatedAt = checkpoint.createdAt;
        return checkpoint;
    }

    /**
     * 이벤트로 상태를 앞으로만 옮긴다. 같은 이벤트가 다시 오거나 순서가 바뀌어 와도 결과가 같다(멱등).
     *
     * @return 실제로 바뀌었으면 true
     */
    public boolean advanceTo(CheckpointStatus next, OffsetDateTime at) {
        if (!status.isBefore(next)) {
            return false;
        }
        if (occurredAt == null && at != null && !next.isBefore(CheckpointStatus.CAPTURED)) {
            occurredAt = Times.utc(at);
        }
        status = next;
        updatedAt = Times.now();
        return true;
    }

    public boolean isSealed() {
        return status == CheckpointStatus.SEALED;
    }

    public Long getId() {
        return id;
    }

    public String getPublicId() {
        return publicId;
    }

    public Rental getRental() {
        return rental;
    }

    public CheckpointType getType() {
        return type;
    }

    public CheckpointStatus getStatus() {
        return status;
    }

    public OffsetDateTime getOccurredAt() {
        return occurredAt;
    }
}
