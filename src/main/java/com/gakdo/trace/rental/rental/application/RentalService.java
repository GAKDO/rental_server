package com.gakdo.trace.rental.rental.application;

import java.util.List;
import java.util.Set;

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

    /** 시험 데이터 기기 시리얼 접두사. 이 접두사의 렌탈만 지울 수 있다. */
    public static final String TEST_SERIAL_PREFIX = "TEST-";

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
        // 부스는 상태를 고를 수 없고 진행 중(OPEN) 렌탈 전체를 본다(부스 태블릿 기기 목록, contracts listRentals)
        RentalStatus effective = user.isBooth() ? RentalStatus.OPEN : status;
        Set<String> scope = user.isAdmin() || user.isBooth() ? null : user.rentals();
        List<Rental> rows = rentals.findBy(
                RentalSpecs.search(effective, scope, cursor),
                query -> query.sortBy(NEWEST_FIRST).limit(limit + 1).all());
        boolean more = rows.size() > limit;
        List<Rental> items = more ? rows.subList(0, limit) : rows;
        // 목록의 지금 시점(currentCheckpoint)을 트랜잭션 안에서 읽어 둔다(@BatchSize로 한 번에)
        items.forEach(Rental::currentCheckpoint);
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

    /**
     * 시험 렌탈 지우기(11/4 민서 요청). 기기 시리얼이 TEST-로 시작하는 렌탈만, 상태와 관계없이 렌탈·시점을 지운다.
     * 그 기기에 다른 렌탈·파일럿 참가가 없으면 기기도 지워 같은 시리얼로 다시 등록할 수 있게 한다.
     * 시점마다 trace.checkpoint.purged를 내서 다른 서버가 자기 데이터를 지운다(채널 트랜잭션이라 커밋될 때 함께 나감).
     */
    @Transactional
    public void deleteTestRental(String rentalId) {
        Rental rental = rentals.lockByPublicId(rentalId).orElseThrow(() -> DomainException.notFound("렌탈"));
        Device device = rental.getDevice();
        if (!isTestSerial(device.getSerial())) {
            throw new DomainException(ErrorCode.NOT_TEST_DATA, "기기 시리얼이 " + TEST_SERIAL_PREFIX + "로 시작하는 렌탈만 지울 수 있습니다.");
        }
        Long deviceId = device.getId();
        String devicePublicId = device.getPublicId();
        String serial = device.getSerial();
        List<CheckpointPurgedEvent> purged = rental.getCheckpoints().stream()
                .map(c -> new CheckpointPurgedEvent(CheckpointPurgedEvent.SCHEMA_VERSION, PublicIds.next(), Times.now(),
                        rental.getPublicId(), c.getPublicId(), c.getType().name(), devicePublicId, serial))
                .toList();
        checkpoints.deleteByRentalId(rental.getId());
        rentals.deleteRow(rental.getId());
        if (!rentals.existsByDevice_Id(deviceId) && !devices.hasPilotParticipant(deviceId)) {
            devices.deleteRow(deviceId);
        }
        purged.forEach(events::publishPurged);
    }

    static boolean isTestSerial(String serial) {
        return serial != null && serial.startsWith(TEST_SERIAL_PREFIX);
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
