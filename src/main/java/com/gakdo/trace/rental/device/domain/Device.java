package com.gakdo.trace.rental.device.domain;

import java.time.OffsetDateTime;

import com.gakdo.trace.rental.common.domain.DomainException;
import com.gakdo.trace.rental.common.domain.ErrorCode;
import com.gakdo.trace.rental.common.infra.Times;
import com.gakdo.trace.rental.common.infra.PublicIds;
import com.gakdo.trace.rental.devicemodel.domain.DeviceModel;

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

/**
 * 기기(rental.device). 시리얼은 기기 신원이라 setter가 없다. 폐기해도 행을 지우지 않는다(FR-RNT-05).
 * 상태 RETIRED ⇔ retiredAt 있음 (DB CHECK와 같은 규칙).
 * 폐기 기기는 수리 중(MAINTENANCE)으로만 되돌린다. 점검 없이 바로 렌탈로 나가지 않게 한다(contracts 0.6.0, 11/2 결정).
 */
@Entity
@Table(name = "device")
public class Device {

    /** DB 기본 키(순번). 밖에 내보내지 않는다. */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 밖에 보이는 ID(NanoID 21자) */
    @Column(name = "public_id", nullable = false, updatable = false, length = 21)
    private String publicId;

    @Column(nullable = false, updatable = false, length = 64)
    private String serial;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "device_model_id", nullable = false, updatable = false)
    private DeviceModel model;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 8)
    private DeviceOs os;

    @Column(length = 32)
    private String color;

    @Column(name = "asset_no", length = 64)
    private String assetNo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private DeviceStatus status;

    @Column(name = "retired_at")
    private OffsetDateTime retiredAt;

    @Column(name = "updated_by", length = 21)
    private String updatedBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    protected Device() {
    }

    public static Device register(String serial, DeviceModel model, DeviceOs os, String color, String assetNo,
            String actor) {
        Device device = new Device();
        device.publicId = PublicIds.next();
        device.serial = serial;
        device.model = model;
        device.os = os;
        device.color = color;
        device.assetNo = assetNo;
        device.status = DeviceStatus.ACTIVE;
        device.updatedBy = actor;
        device.createdAt = Times.now();
        device.updatedAt = device.createdAt;
        return device;
    }

    /**
     * 색상·자산번호·OS·상태를 바꾼다. 진행 중 렌탈 검사는 호출하는 쪽(서비스)이 잠금 안에서 먼저 한다.
     * 상태 전이가 규칙에 어긋나면 다른 값도 바꾸지 않고 409로 끝낸다.
     */
    public void change(DeviceChanges changes, String actor) {
        if (changes.status() != null) {
            checkTransition(changes.status());
        }
        if (changes.color() != null) {
            this.color = changes.color().value();
        }
        if (changes.assetNo() != null) {
            this.assetNo = changes.assetNo().value();
        }
        if (changes.os() != null) {
            this.os = changes.os();
        }
        if (changes.status() != null) {
            changeStatus(changes.status());
        }
        this.updatedBy = actor;
        this.updatedAt = Times.now();
    }

    /** RETIRED → ACTIVE만 막는다. 나머지 전이(같은 상태 포함)는 허용한다. */
    private void checkTransition(DeviceStatus next) {
        if (status == DeviceStatus.RETIRED && next == DeviceStatus.ACTIVE) {
            throw new DomainException(ErrorCode.DEVICE_STATUS_TRANSITION_INVALID,
                    "수리 중으로 바꿔 점검한 뒤 사용 가능으로 바꿔 주세요.");
        }
    }

    private void changeStatus(DeviceStatus next) {
        if (next == status) {
            return;
        }
        this.status = next;
        // 폐기하면 시각을 남기고, 관리자가 되돌리면 지운다(DB CHECK와 일치)
        this.retiredAt = next == DeviceStatus.RETIRED ? Times.now() : null;
    }

    /** 렌탈을 만들 수 있는 상태인가(수리 중·폐기 기기는 렌탈로 나가지 않는다). */
    public boolean isRentable() {
        return status == DeviceStatus.ACTIVE;
    }

    public Long getId() {
        return id;
    }

    public String getPublicId() {
        return publicId;
    }

    public String getSerial() {
        return serial;
    }

    public DeviceModel getModel() {
        return model;
    }

    public DeviceOs getOs() {
        return os;
    }

    public String getColor() {
        return color;
    }

    public String getAssetNo() {
        return assetNo;
    }

    public DeviceStatus getStatus() {
        return status;
    }

    public OffsetDateTime getRetiredAt() {
        return retiredAt;
    }

    public String getUpdatedBy() {
        return updatedBy;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }
}
