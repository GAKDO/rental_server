package com.gakdo.trace.rental.rental.domain;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import org.hibernate.annotations.BatchSize;

import com.gakdo.trace.rental.common.domain.DomainException;
import com.gakdo.trace.rental.common.domain.ErrorCode;
import com.gakdo.trace.rental.common.infra.Times;
import com.gakdo.trace.rental.common.infra.PublicIds;
import com.gakdo.trace.rental.device.domain.Device;

import jakarta.persistence.CascadeType;
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
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;

/**
 * 렌탈(rental.rental). 출고·수령·반납 세 시점을 순서대로 가진다(FR-RNT-02).
 * 순서 규칙은 여기 한 곳에 있고, DB UNIQUE(rental_id, type)가 동시 요청의 마지막 방어선이다.
 */
@Entity
@Table(name = "rental")
public class Rental {

    /** DB 기본 키(순번). 밖에 내보내지 않는다. */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 밖에 보이는 ID(NanoID 21자) */
    @Column(name = "public_id", nullable = false, updatable = false, length = 21)
    private String publicId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "device_id", nullable = false, updatable = false)
    private Device device;

    @Column(name = "renter_ref", nullable = false, length = 128)
    private String renterRef;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private RentalStatus status;

    @Column(name = "started_at")
    private OffsetDateTime startedAt;

    @Column(name = "ended_at")
    private OffsetDateTime endedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    // 목록에서 렌탈마다 시점을 읽으므로 한 번에 묶어 읽는다(N+1 방지)
    @BatchSize(size = 100)
    @OneToMany(mappedBy = "rental", cascade = CascadeType.PERSIST)
    private List<Checkpoint> checkpoints = new ArrayList<>();

    protected Rental() {
    }

    /** ACTIVE 기기로만 만든다. 진행 중 렌탈 중복은 서비스·DB 부분 고유 인덱스가 막는다. */
    public static Rental open(Device device, String renterRef) {
        if (!device.isRentable()) {
            throw new DomainException(ErrorCode.DEVICE_NOT_ACTIVE, "수리 중이거나 폐기된 기기는 렌탈을 만들 수 없습니다.");
        }
        Rental rental = new Rental();
        rental.publicId = PublicIds.next();
        rental.device = device;
        rental.renterRef = renterRef;
        rental.status = RentalStatus.OPEN;
        rental.createdAt = Times.now();
        rental.updatedAt = rental.createdAt;
        return rental;
    }

    /**
     * 시점을 시작한다. OUT → RCV → RET 순서로 하나씩, 앞 시점이 봉인(SEALED)된 뒤에만, 렌탈이 OPEN일 때만.
     * OUT을 시작하면 startedAt이 채워진다.
     */
    public Checkpoint startCheckpoint(CheckpointType type) {
        if (status != RentalStatus.OPEN) {
            throw orderViolation("진행 중(OPEN)인 렌탈에서만 시점을 시작할 수 있습니다.");
        }
        if (find(type).isPresent()) {
            throw orderViolation(type + " 시점이 이미 있습니다.");
        }
        CheckpointType previous = type.previous();
        if (previous != null && find(previous).filter(Checkpoint::isSealed).isEmpty()) {
            throw orderViolation(label(previous) + "(" + previous + ")가 봉인된 뒤에 " + label(type) + "(" + type
                    + ")을 시작할 수 있습니다.");
        }
        Checkpoint checkpoint = Checkpoint.open(this, type);
        checkpoints.add(checkpoint);
        if (type == CheckpointType.OUT) {
            startedAt = Times.now();
        }
        updatedAt = Times.now();
        return checkpoint;
    }

    /** 시점이 봉인되면 부른다. 반납(RET)이 봉인되면 RETURNED. */
    public void onCheckpointSealed(Checkpoint checkpoint) {
        if (checkpoint.getType() == CheckpointType.RET && status == RentalStatus.OPEN) {
            status = RentalStatus.RETURNED;
            updatedAt = Times.now();
        }
    }

    /** 반납이 봉인된(RETURNED) 렌탈만 종료한다. */
    public void close() {
        if (status != RentalStatus.RETURNED) {
            throw new DomainException(ErrorCode.RENTAL_NOT_CLOSABLE, "반납 시점이 봉인된 뒤에 종료할 수 있습니다.");
        }
        status = RentalStatus.CLOSED;
        endedAt = Times.now();
        updatedAt = endedAt;
    }

    public Optional<Checkpoint> find(CheckpointType type) {
        return checkpoints.stream().filter(c -> c.getType() == type).findFirst();
    }

    /** 지금 시점 = 시작된 시점 중 가장 뒤(OUT → RCV → RET). 아직 없으면 빈 값. */
    public Optional<Checkpoint> currentCheckpoint() {
        return checkpoints.stream().max(Comparator.comparing(Checkpoint::getType));
    }

    /** OUT·RCV·RET 순 */
    public List<Checkpoint> getCheckpoints() {
        return checkpoints.stream().sorted(Comparator.comparing(Checkpoint::getType)).toList();
    }

    private static DomainException orderViolation(String detail) {
        return new DomainException(ErrorCode.CHECKPOINT_ORDER_VIOLATION, detail);
    }

    private static String label(CheckpointType type) {
        return switch (type) {
            case OUT -> "출고";
            case RCV -> "수령";
            case RET -> "반납";
        };
    }

    public Long getId() {
        return id;
    }

    public String getPublicId() {
        return publicId;
    }

    public Device getDevice() {
        return device;
    }

    public String getRenterRef() {
        return renterRef;
    }

    public RentalStatus getStatus() {
        return status;
    }

    public OffsetDateTime getStartedAt() {
        return startedAt;
    }

    public OffsetDateTime getEndedAt() {
        return endedAt;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
