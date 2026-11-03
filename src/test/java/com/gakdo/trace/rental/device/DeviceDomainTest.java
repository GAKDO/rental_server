package com.gakdo.trace.rental.device;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gakdo.trace.rental.common.domain.DomainException;
import com.gakdo.trace.rental.common.domain.ErrorCode;
import com.gakdo.trace.rental.device.domain.Device;
import com.gakdo.trace.rental.device.domain.DeviceChanges;
import com.gakdo.trace.rental.device.domain.DeviceChanges.TextChange;
import com.gakdo.trace.rental.device.domain.DeviceOs;
import com.gakdo.trace.rental.device.domain.DeviceStatus;
import com.gakdo.trace.rental.devicemodel.domain.DeviceModel;
import com.gakdo.trace.rental.devicemodel.domain.Dimensions;
import com.gakdo.trace.rental.support.Fixtures;

class DeviceDomainTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("기기는 ACTIVE로 시작하고 publicId·변경자(updated_by)를 남긴다")
    void registerStartsActive() {
        Device device = Fixtures.device("C02EXAMPLE01");
        assertThat(device.getStatus()).isEqualTo(DeviceStatus.ACTIVE);
        assertThat(device.isRentable()).isTrue();
        assertThat(device.getPublicId()).hasSize(21);
        assertThat(device.getUpdatedBy()).isEqualTo("admin0000000000000001");
        assertThat(device.getRetiredAt()).isNull();
    }

    @Test
    @DisplayName("폐기하면 retiredAt이 채워지고, 수리 중으로 되돌리면 retiredAt이 비워진다")
    void retireAndRestoreToMaintenance() {
        Device device = Fixtures.device("C02EXAMPLE01");
        device.change(new DeviceChanges(null, null, null, DeviceStatus.RETIRED), "admin0000000000000002");
        assertThat(device.getStatus()).isEqualTo(DeviceStatus.RETIRED);
        assertThat(device.getRetiredAt()).isNotNull();
        assertThat(device.isRentable()).isFalse();
        assertThat(device.getUpdatedBy()).isEqualTo("admin0000000000000002");

        device.change(new DeviceChanges(null, null, null, DeviceStatus.MAINTENANCE), "admin0000000000000003");
        assertThat(device.getStatus()).isEqualTo(DeviceStatus.MAINTENANCE);
        assertThat(device.getRetiredAt()).isNull();
        assertThat(device.getUpdatedBy()).isEqualTo("admin0000000000000003");

        device.change(new DeviceChanges(null, null, null, DeviceStatus.ACTIVE), null);
        assertThat(device.isRentable()).isTrue();
    }

    @Test
    @DisplayName("폐기 기기를 사용 가능으로 바로 바꾸면 409 DEVICE_STATUS_TRANSITION_INVALID이고 아무것도 바뀌지 않는다")
    void retiredToActiveRejected() {
        Device device = Fixtures.device("C02EXAMPLE01");
        device.change(new DeviceChanges(null, null, null, DeviceStatus.RETIRED), "admin0000000000000002");
        var retiredAt = device.getRetiredAt();

        assertThatThrownBy(() -> device.change(
                new DeviceChanges(TextChange.clear(), null, null, DeviceStatus.ACTIVE), "admin0000000000000003"))
                .isInstanceOfSatisfying(DomainException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.DEVICE_STATUS_TRANSITION_INVALID));
        assertThat(device.getStatus()).isEqualTo(DeviceStatus.RETIRED);
        assertThat(device.getRetiredAt()).isEqualTo(retiredAt);
        assertThat(device.getColor()).isNotNull();
        assertThat(device.getUpdatedBy()).isEqualTo("admin0000000000000002");
    }

    @Test
    @DisplayName("수리 중 기기는 렌탈할 수 없다")
    void maintenanceNotRentable() {
        Device device = Fixtures.device("C02EXAMPLE01");
        device.change(new DeviceChanges(null, null, null, DeviceStatus.MAINTENANCE), null);
        assertThat(device.isRentable()).isFalse();
        assertThat(device.getRetiredAt()).isNull();
    }

    @Test
    @DisplayName("PATCH: 생략한 값은 그대로, null은 지우고, 시리얼은 그대로다")
    void changeKeepsSerial() {
        Device device = Fixtures.device("C02EXAMPLE01");
        device.change(new DeviceChanges(TextChange.clear(), TextChange.of("A-0002"), DeviceOs.WINDOWS, null), null);
        assertThat(device.getColor()).isNull();
        assertThat(device.getAssetNo()).isEqualTo("A-0002");
        assertThat(device.getOs()).isEqualTo(DeviceOs.WINDOWS);
        assertThat(device.getSerial()).isEqualTo("C02EXAMPLE01");
        assertThat(device.getStatus()).isEqualTo(DeviceStatus.ACTIVE);
    }

    @Test
    @DisplayName("시리얼은 등록 뒤 바꿀 수 없다: PATCH 본문에 serial·modelCode가 있으면 400")
    void serialImmutable() throws Exception {
        assertThatThrownBy(() -> parse("{\"serial\":\"X\",\"modelCode\":\"Y\"}"))
                .isInstanceOfSatisfying(DomainException.class, e -> {
                    assertThat(e.code()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                    assertThat(e.errors()).extracting(DomainException.FieldError::field)
                            .containsExactlyInAnyOrder("serial", "modelCode");
                });
    }

    @Test
    @DisplayName("PATCH 본문 형식: 빈 본문·모르는 값·너무 긴 값은 400")
    void parseRejectsInvalid() {
        assertThatThrownBy(() -> parse("{}")).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> parse("{\"status\":\"BROKEN\"}")).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> parse("{\"os\":1}")).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> parse("{\"color\":123}")).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> parse("{\"color\":\"" + "x".repeat(33) + "\"}")).isInstanceOf(DomainException.class);
    }

    @Test
    @DisplayName("PATCH 본문: color null은 지우기, 생략은 그대로")
    void parseNullVsMissing() throws Exception {
        DeviceChanges changes = parse("{\"color\":null,\"status\":\"MAINTENANCE\",\"os\":\"MACOS\"}");
        assertThat(changes.color()).isEqualTo(TextChange.clear());
        assertThat(changes.assetNo()).isNull();
        assertThat(changes.status()).isEqualTo(DeviceStatus.MAINTENANCE);
        assertThat(changes.os()).isEqualTo(DeviceOs.MACOS);
    }

    @Test
    @DisplayName("기종 치수는 소수 한 자리로 반올림해 저장하고, 다시 저장하면 값이 바뀐다")
    void modelDimensionsRounded() {
        DeviceModel model = Fixtures.model();
        DeviceModel.Attributes a = Fixtures.mba13();
        model.apply(new DeviceModel.Attributes(a.name(), a.manufacturer(), a.formFactor(), null,
                new Dimensions(new BigDecimal("304.15"), new BigDecimal("215"), new BigDecimal("7.04"),
                        new BigDecimal("4.3"))), "admin0000000000000002");
        assertThat(model.getDimensions().widthMm()).isEqualByComparingTo("304.2");
        assertThat(model.getDimensions().depthMm()).isEqualByComparingTo("215.0");
        assertThat(model.getDimensions().baseHMm()).isEqualByComparingTo("7.0");
        assertThat(model.getSpec()).isNull();
        assertThat(model.getUpdatedBy()).isEqualTo("admin0000000000000002");
    }

    private DeviceChanges parse(String json) throws Exception {
        var method = Class.forName("com.gakdo.trace.rental.device.api.DeviceUpdateParser")
                .getDeclaredMethod("parse", com.fasterxml.jackson.databind.JsonNode.class);
        method.setAccessible(true);
        try {
            return (DeviceChanges) method.invoke(null, mapper.readTree(json));
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw (Exception) e.getCause();
        }
    }
}
