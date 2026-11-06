package com.gakdo.trace.rental.pilot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.gakdo.trace.rental.common.domain.DomainException;
import com.gakdo.trace.rental.common.domain.ErrorCode;
import com.gakdo.trace.rental.device.domain.Device;
import com.gakdo.trace.rental.device.infra.DeviceRepository;
import com.gakdo.trace.rental.pilot.application.PilotService;
import com.gakdo.trace.rental.pilot.domain.PilotParticipant;
import com.gakdo.trace.rental.pilot.infra.PilotParticipantRepository;
import com.gakdo.trace.rental.support.Fixtures;

class PilotServiceTest {

    private final PilotParticipantRepository participants = mock(PilotParticipantRepository.class);
    private final DeviceRepository devices = mock(DeviceRepository.class);
    private final PilotService service = new PilotService(participants, devices);
    private final Device device = Fixtures.device("C02PILOT0001");
    private final OffsetDateTime consent = OffsetDateTime.parse("2026-11-05T14:00:00+09:00");

    @Test
    @DisplayName("등록하면 별칭과 기기 ID만 저장하고 동의 시각은 UTC로 둔다")
    void registers() {
        when(devices.findWithModelByPublicId(device.getPublicId())).thenReturn(Optional.of(device));
        when(participants.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));

        PilotService.Registered r = service.register("P01", device.getPublicId(), consent);

        assertThat(r.alias()).isEqualTo("P01");
        assertThat(r.deviceId()).isEqualTo(device.getPublicId());
        assertThat(r.consentAt()).isEqualTo(consent.toInstant().atOffset(java.time.ZoneOffset.UTC));
        assertThat(r.participantId()).matches("^[A-Za-z0-9_-]{21}$");
    }

    @Test
    @DisplayName("이미 쓰는 별칭은 409, 없는 기기는 404, 미래 동의 시각은 400이다")
    void rejects() {
        when(devices.findWithModelByPublicId(device.getPublicId())).thenReturn(Optional.of(device));
        when(participants.existsByAlias("P01")).thenReturn(true);
        assertThatThrownBy(() -> service.register("P01", device.getPublicId(), consent))
                .extracting(e -> ((DomainException) e).code()).isEqualTo(ErrorCode.PILOT_ALIAS_EXISTS);
        assertThatThrownBy(() -> service.register("P02", "zzzzzzzzzzzzzzzzzzzzz", consent))
                .extracting(e -> ((DomainException) e).code()).isEqualTo(ErrorCode.NOT_FOUND);
        assertThatThrownBy(() -> service.register("P02", device.getPublicId(), OffsetDateTime.now().plusDays(1)))
                .extracting(e -> ((DomainException) e).code()).isEqualTo(ErrorCode.VALIDATION_FAILED);
        verify(participants, never()).saveAndFlush(any(PilotParticipant.class));
    }
}
