package com.gakdo.trace.rental.rental;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.gakdo.trace.rental.common.domain.DomainException;
import com.gakdo.trace.rental.common.domain.ErrorCode;
import com.gakdo.trace.rental.device.infra.DeviceRepository;
import com.gakdo.trace.rental.rental.application.CheckpointEventPublisher;
import com.gakdo.trace.rental.rental.application.CheckpointPurgedEvent;
import com.gakdo.trace.rental.rental.application.RentalService;
import com.gakdo.trace.rental.rental.domain.CheckpointType;
import com.gakdo.trace.rental.rental.domain.Rental;
import com.gakdo.trace.rental.rental.infra.CheckpointRepository;
import com.gakdo.trace.rental.rental.infra.RentalRepository;
import com.gakdo.trace.rental.support.Fixtures;

class RentalDeleteServiceTest {

    private final RentalRepository rentals = mock(RentalRepository.class);
    private final CheckpointRepository checkpoints = mock(CheckpointRepository.class);
    private final DeviceRepository devices = mock(DeviceRepository.class);
    private final CheckpointEventPublisher events = mock(CheckpointEventPublisher.class);
    private RentalService service;

    @BeforeEach
    void setUp() {
        service = new RentalService(rentals, checkpoints, devices, events);
    }

    @Test
    @DisplayName("시험 렌탈을 지우면 시점·렌탈·기기를 지우고 시점마다 trace.checkpoint.purged를 낸다")
    void deletesTestRentalAndPublishesPerCheckpoint() {
        Rental rental = Rental.open(Fixtures.device("TEST-0002"), "TEST-SAMPLE");
        String out = rental.startCheckpoint(CheckpointType.OUT).getPublicId();
        when(rentals.lockByPublicId(rental.getPublicId())).thenReturn(Optional.of(rental));
        when(rentals.existsByDevice_Id(any())).thenReturn(false);
        when(devices.hasPilotParticipant(any())).thenReturn(false);

        service.deleteTestRental(rental.getPublicId());

        verify(checkpoints).deleteByRentalId(any());
        verify(rentals).deleteRow(any());
        verify(devices).deleteRow(any());
        ArgumentCaptor<CheckpointPurgedEvent> sent = ArgumentCaptor.forClass(CheckpointPurgedEvent.class);
        verify(events, times(1)).publishPurged(sent.capture());
        assertThat(sent.getValue().checkpointId()).isEqualTo(out);
        assertThat(sent.getValue().rentalId()).isEqualTo(rental.getPublicId());
        assertThat(sent.getValue().checkpointType()).isEqualTo("OUT");
        assertThat(sent.getValue().serial()).isEqualTo("TEST-0002");
        assertThat(sent.getValue().schemaVersion()).isEqualTo("1.0");
    }

    @Test
    @DisplayName("기기에 다른 렌탈이 남아 있으면 기기는 지우지 않는다")
    void keepsDeviceWithOtherRentals() {
        Rental rental = Rental.open(Fixtures.device("TEST-0003"), "TEST-SAMPLE");
        when(rentals.lockByPublicId(rental.getPublicId())).thenReturn(Optional.of(rental));
        when(rentals.existsByDevice_Id(any())).thenReturn(true);

        service.deleteTestRental(rental.getPublicId());

        verify(rentals).deleteRow(any());
        verify(devices, never()).deleteRow(any());
        verify(events, never()).publishPurged(any());
    }

    @Test
    @DisplayName("시리얼이 TEST-로 시작하지 않는 렌탈은 409 NOT_TEST_DATA이고 아무것도 지우지 않는다")
    void refusesRealRental() {
        Rental rental = Rental.open(Fixtures.device("C02REAL0001"), "R-2026-0012");
        when(rentals.lockByPublicId(rental.getPublicId())).thenReturn(Optional.of(rental));

        assertThatThrownBy(() -> service.deleteTestRental(rental.getPublicId()))
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).code()).isEqualTo(ErrorCode.NOT_TEST_DATA);
        verify(checkpoints, never()).deleteByRentalId(any());
        verify(rentals, never()).deleteRow(any());
        verify(events, never()).publishPurged(any());
    }

    @Test
    @DisplayName("없는 렌탈은 404")
    void missingRental() {
        when(rentals.lockByPublicId("Ws0HLv6QRbDILPWOcz5I2")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.deleteTestRental("Ws0HLv6QRbDILPWOcz5I2"))
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).code()).isEqualTo(ErrorCode.NOT_FOUND);
    }
}
