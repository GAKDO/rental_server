package com.gakdo.trace.rental.device;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import com.gakdo.trace.rental.common.domain.DomainException;
import com.gakdo.trace.rental.common.domain.ErrorCode;
import com.gakdo.trace.rental.device.application.DeviceService;
import com.gakdo.trace.rental.device.domain.Device;
import com.gakdo.trace.rental.device.infra.DeviceRepository;
import com.gakdo.trace.rental.devicemodel.application.DeviceModelService;
import com.gakdo.trace.rental.devicemodel.domain.DeviceModel;
import com.gakdo.trace.rental.devicemodel.infra.DeviceModelRepository;
import com.gakdo.trace.rental.rental.application.RentalService;
import com.gakdo.trace.rental.rental.infra.RentalRepository;
import com.gakdo.trace.rental.support.Fixtures;

/** 기기·기종 지우기(11/10). DB 없이 규칙만 본다. */
class DeviceDeleteServiceTest {

    private final DeviceRepository devices = mock(DeviceRepository.class);
    private final DeviceModelRepository models = mock(DeviceModelRepository.class);
    private final RentalRepository rentals = mock(RentalRepository.class);
    private final RentalService rentalService = mock(RentalService.class);
    private DeviceService deviceService;
    private DeviceModelService modelService;

    @BeforeEach
    void setUp() {
        deviceService = new DeviceService(devices, models, rentals, rentalService);
        modelService = new DeviceModelService(models, devices);
    }

    private Device given(String serial) {
        Device device = Fixtures.device(serial);
        when(devices.lockByPublicId(device.getPublicId())).thenReturn(Optional.of(device));
        return device;
    }

    @Test
    @DisplayName("렌탈·파일럿 이력이 없는 실제 기기는 지운다")
    void deletesUnusedDevice() {
        Device device = given("C02REAL0001");
        when(rentals.existsByDevice_Id(any())).thenReturn(false);

        deviceService.delete(device.getPublicId());

        verify(devices).deleteRow(any());
        verify(rentalService, never()).purgeAllOf(any());
    }

    @Test
    @DisplayName("렌탈 이력이 있는 실제 기기는 409 DEVICE_IN_USE이고 아무것도 지우지 않는다")
    void refusesRealDeviceWithRentals() {
        Device device = given("C02REAL0002");
        when(rentals.existsByDevice_Id(any())).thenReturn(true);

        String id = device.getPublicId();
        assertThatThrownBy(() -> deviceService.delete(id)).isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).code()).isEqualTo(ErrorCode.DEVICE_IN_USE);
        verify(devices, never()).deleteRow(any());
        verify(rentalService, never()).purgeAllOf(any());
    }

    @Test
    @DisplayName("TEST- 시험 기기는 렌탈이 있어도 렌탈을 시험 렌탈 지우기처럼 함께 지우고 기기도 지운다")
    void deletesTestDeviceWithRentals() {
        Device device = given("TEST-0003");
        when(rentals.existsByDevice_Id(any())).thenReturn(true);

        deviceService.delete(device.getPublicId());

        verify(rentalService).purgeAllOf(device);
        verify(devices).deleteRow(any());
    }

    @Test
    @DisplayName("파일럿 참가 기록이 있는 기기는 시험 기기라도 409 DEVICE_IN_USE")
    void refusesPilotDevice() {
        Device device = given("TEST-0004");
        when(devices.hasPilotParticipant(any())).thenReturn(true);

        String id = device.getPublicId();
        assertThatThrownBy(() -> deviceService.delete(id)).isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).code()).isEqualTo(ErrorCode.DEVICE_IN_USE);
        verify(rentalService, never()).purgeAllOf(any());
        verify(devices, never()).deleteRow(any());
    }

    @Test
    @DisplayName("없는 기기를 지우면 404")
    void missingDevice() {
        when(devices.lockByPublicId("Ws0HLv6QRbDILPWOcz5I2")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> deviceService.delete("Ws0HLv6QRbDILPWOcz5I2")).isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).code()).isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    @DisplayName("기기가 없는 기종은 지운다")
    void deletesUnusedModel() {
        DeviceModel model = Fixtures.model();
        when(models.findByModelCode("TEST-MODEL")).thenReturn(Optional.of(model));
        when(devices.countByModel_Id(any())).thenReturn(0L);

        modelService.delete("TEST-MODEL");

        verify(models).delete(model);
    }

    @Test
    @DisplayName("기기가 등록된 기종은 409 DEVICE_MODEL_IN_USE이고 지우지 않는다")
    void refusesModelInUse() {
        DeviceModel model = Fixtures.model();
        when(models.findByModelCode("MBA13-M2")).thenReturn(Optional.of(model));
        when(devices.countByModel_Id(any())).thenReturn(2L);

        assertThatThrownBy(() -> modelService.delete("MBA13-M2")).isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).code()).isEqualTo(ErrorCode.DEVICE_MODEL_IN_USE);
        verify(models, never()).delete(any(DeviceModel.class));
    }

    @Test
    @DisplayName("확인 뒤 기기가 동시에 등록돼 외래 키가 막으면 같은 409로 바꾼다")
    void modelRaceBecomesConflict() {
        DeviceModel model = Fixtures.model();
        when(models.findByModelCode("MBA13-M2")).thenReturn(Optional.of(model));
        doThrow(new DataIntegrityViolationException("fk")).when(models).flush();

        assertThatThrownBy(() -> modelService.delete("MBA13-M2")).isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).code()).isEqualTo(ErrorCode.DEVICE_MODEL_IN_USE);
    }

    @Test
    @DisplayName("없는 기종을 지우면 404")
    void missingModel() {
        when(models.findByModelCode("NOPE")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> modelService.delete("NOPE")).isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).code()).isEqualTo(ErrorCode.NOT_FOUND);
    }
}
