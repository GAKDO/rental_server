package com.gakdo.trace.rental.device.application;

import java.util.List;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.gakdo.trace.rental.common.domain.DomainException;
import com.gakdo.trace.rental.common.domain.ErrorCode;
import com.gakdo.trace.rental.common.infra.Cursor;
import com.gakdo.trace.rental.device.domain.Device;
import com.gakdo.trace.rental.device.domain.DeviceChanges;
import com.gakdo.trace.rental.device.domain.DeviceOs;
import com.gakdo.trace.rental.device.domain.DeviceStatus;
import com.gakdo.trace.rental.device.infra.DeviceRepository;
import com.gakdo.trace.rental.device.infra.DeviceSpecs;
import com.gakdo.trace.rental.devicemodel.domain.DeviceModel;
import com.gakdo.trace.rental.devicemodel.infra.DeviceModelRepository;
import com.gakdo.trace.rental.rental.domain.RentalStatus;
import com.gakdo.trace.rental.rental.infra.RentalRepository;

/** 기기 등록·수정·검색 (FR-RNT-01, FR-RNT-05). 삭제는 없다. */
@Service
public class DeviceService {

    private static final Sort NEWEST_FIRST = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("publicId"));

    private final DeviceRepository devices;
    private final DeviceModelRepository models;
    private final RentalRepository rentals;

    public DeviceService(DeviceRepository devices, DeviceModelRepository models, RentalRepository rentals) {
        this.devices = devices;
        this.models = models;
        this.rentals = rentals;
    }

    @Transactional
    public Device register(String serial, String modelCode, DeviceOs os, String color, String assetNo, String actor) {
        DeviceModel model = models.findByModelCode(modelCode).orElseThrow(() -> DomainException.notFound("기종"));
        if (devices.existsBySerial(serial)) {
            throw serialExists();
        }
        try {
            return devices.saveAndFlush(Device.register(serial, model, os, color, assetNo, actor));
        } catch (DataIntegrityViolationException e) {
            // 같은 시리얼이 동시에 들어온 경우: UNIQUE(serial)가 막는다
            throw serialExists();
        }
    }

    @Transactional
    public Device change(String deviceId, DeviceChanges changes, String actor) {
        Device device = devices.lockByPublicId(deviceId).orElseThrow(() -> DomainException.notFound("기기"));
        DeviceStatus next = changes.status();
        boolean leavingService = next == DeviceStatus.MAINTENANCE || next == DeviceStatus.RETIRED;
        if (leavingService && next != device.getStatus()
                && rentals.existsByDevice_IdAndStatus(device.getId(), RentalStatus.OPEN)) {
            throw new DomainException(ErrorCode.DEVICE_HAS_OPEN_RENTAL,
                    "렌탈을 종료한 뒤에 수리 중이나 폐기로 바꿀 수 있습니다.");
        }
        device.change(changes, actor);
        return device;
    }

    /** 기기 상세(11/6 추가, 기기별 렌탈 이력 화면 머리글). 없으면 404. */
    @Transactional(readOnly = true)
    public Device get(String deviceId) {
        return devices.findWithModelByPublicId(deviceId).orElseThrow(() -> DomainException.notFound("기기"));
    }

    /** limit+1개를 읽어 다음 쪽이 있는지 안다. */
    @Transactional(readOnly = true)
    public Page search(String modelCode, DeviceStatus status, String q, Cursor cursor, int limit) {
        List<Device> rows = devices.findBy(DeviceSpecs.search(modelCode, status, q, cursor),
                query -> query.sortBy(NEWEST_FIRST).limit(limit + 1).all());
        boolean more = rows.size() > limit;
        List<Device> items = more ? rows.subList(0, limit) : rows;
        String next = more ? new Cursor(items.get(limit - 1).getCreatedAt(), items.get(limit - 1).getPublicId()).encode() : null;
        items.forEach(d -> d.getModel().getName()); // 트랜잭션 안에서 기종을 읽어 둔다
        // "총 N건"(11/10): 커서 없이 같은 조건으로 센다
        long total = devices.count(DeviceSpecs.search(modelCode, status, q, null));
        return new Page(List.copyOf(items), next, total);
    }

    /** @param totalCount 같은 조건에 맞는 전체 건수 */
    public record Page(List<Device> items, String nextCursor, long totalCount) {

        public Page(List<Device> items, String nextCursor) {
            this(items, nextCursor, items.size());
        }
    }

    private static DomainException serialExists() {
        return new DomainException(ErrorCode.DEVICE_SERIAL_EXISTS, "같은 시리얼의 기기가 이미 있습니다.");
    }
}
