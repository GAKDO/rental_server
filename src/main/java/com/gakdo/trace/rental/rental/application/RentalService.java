package com.gakdo.trace.rental.rental.application;

import java.util.List;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.gakdo.trace.rental.common.domain.DomainException;
import com.gakdo.trace.rental.common.domain.ErrorCode;
import com.gakdo.trace.rental.common.infra.Cursor;
import com.gakdo.trace.rental.common.infra.CurrentUser;
import com.gakdo.trace.rental.common.infra.PublicIds;
import com.gakdo.trace.rental.common.infra.Times;
import com.gakdo.trace.rental.device.domain.Device;
import com.gakdo.trace.rental.device.infra.DeviceRepository;
import com.gakdo.trace.rental.rental.domain.Checkpoint;
import com.gakdo.trace.rental.rental.domain.CheckpointType;
import com.gakdo.trace.rental.rental.domain.Rental;
import com.gakdo.trace.rental.rental.domain.RentalStatus;
import com.gakdo.trace.rental.rental.infra.CheckpointRepository;
import com.gakdo.trace.rental.rental.infra.RentalRepository;
import com.gakdo.trace.rental.rental.infra.RentalSpecs;

/** 렌탈 생성·조회·시점 시작·종료 (FR-RNT-01, FR-RNT-02, FR-USR-03 목록 범위). */
@Service
public class RentalService {

    private static final Sort NEWEST_FIRST = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("publicId"));

    private final RentalRepository rentals;
    private final CheckpointRepository checkpoints;
    private final DeviceRepository devices;
    private final CheckpointEventPublisher events;

    public RentalService(RentalRepository rentals, CheckpointRepository checkpoints, DeviceRepository devices,
            CheckpointEventPublisher events) {
        this.rentals = rentals;
        this.checkpoints = checkpoints;
        this.devices = devices;
        this.events = events;
    }

    /**
     * 기기 행을 잠근 뒤 상태·진행 중 렌탈을 본다. 잠금을 놓친 경쟁도 ux_rental_open_device가 막아 409로 바꾼다.
     */
    @Transactional
    public Rental open(String deviceId, String renterRef) {
        Device device = devices.lockByPublicId(deviceId).orElseThrow(() -> DomainException.notFound("기기"));
        Rental rental = Rental.open(device, renterRef);
        if (rentals.existsByDevice_IdAndStatus(device.getId(), RentalStatus.OPEN)) {
            throw openExists();
        }
        try {
            return rentals.saveAndFlush(rental);
        } catch (DataIntegrityViolationException e) {
            throw openExists();
        }
    }

    @Transactional(readOnly = true)
    public Rental get(String rentalId, CurrentUser user) {
        Rental rental = rentals.findWithCheckpointsByPublicId(rentalId).orElseThrow(() -> DomainException.notFound("렌탈"));
        if (!user.canSee(rentalId)) {
            throw new DomainException(ErrorCode.OUT_OF_SCOPE, "내 계정에 연결된 렌탈만 볼 수 있습니다.");
        }
        return rental;
    }

    @Transactional(readOnly = true)
    public Page list(RentalStatus status, Cursor cursor, int limit, CurrentUser user) {
        List<Rental> rows = rentals.findBy(
                RentalSpecs.search(status, user.isAdmin() ? null : user.rentals(), cursor),
                query -> query.sortBy(NEWEST_FIRST).limit(limit + 1).all());
        boolean more = rows.size() > limit;
        List<Rental> items = more ? rows.subList(0, limit) : rows;
        String next = more ? new Cursor(items.get(limit - 1).getCreatedAt(), items.get(limit - 1).getPublicId()).encode() : null;
        return new Page(List.copyOf(items), next);
    }

    @Transactional
    public Checkpoint startCheckpoint(String rentalId, CheckpointType type) {
        Rental rental = rentals.lockByPublicId(rentalId).orElseThrow(() -> DomainException.notFound("렌탈"));
        Checkpoint checkpoint = rental.startCheckpoint(type);
        Checkpoint saved;
        try {
            saved = checkpoints.saveAndFlush(checkpoint);
        } catch (DataIntegrityViolationException e) {
            throw new DomainException(ErrorCode.CHECKPOINT_ORDER_VIOLATION, type + " 시점이 이미 있습니다.");
        }
        Device device = rental.getDevice();
        events.publishStarted(new CheckpointStartedEvent(CheckpointStartedEvent.SCHEMA_VERSION, PublicIds.next(),
                Times.now(), rental.getPublicId(), saved.getPublicId(), type.name(), device.getPublicId(),
                device.getSerial(), device.getModel().getModelCode(), device.getModel().getName()));
        return saved;
    }

    @Transactional
    public void close(String rentalId) {
        rentals.lockByPublicId(rentalId).orElseThrow(() -> DomainException.notFound("렌탈")).close();
    }

    public record Page(List<Rental> items, String nextCursor) {
    }

    private static DomainException openExists() {
        return new DomainException(ErrorCode.RENTAL_OPEN_EXISTS, "같은 기기로 진행 중인 렌탈은 하나만 만들 수 있습니다.");
    }
}
