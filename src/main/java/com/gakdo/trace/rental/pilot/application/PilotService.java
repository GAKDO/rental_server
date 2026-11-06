package com.gakdo.trace.rental.pilot.application;

import java.time.OffsetDateTime;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.gakdo.trace.rental.common.domain.DomainException;
import com.gakdo.trace.rental.common.domain.ErrorCode;
import com.gakdo.trace.rental.common.infra.PublicIds;
import com.gakdo.trace.rental.common.infra.Times;
import com.gakdo.trace.rental.device.domain.Device;
import com.gakdo.trace.rental.device.infra.DeviceRepository;
import com.gakdo.trace.rental.pilot.domain.PilotParticipant;
import com.gakdo.trace.rental.pilot.infra.PilotParticipantRepository;

/** 파일럿 참여자 별칭 등록(FR-PIL-01, RE2). 별칭은 겹치면 409 PILOT_ALIAS_EXISTS, 없는 기기는 404. */
@Service
public class PilotService {

    private final PilotParticipantRepository participants;
    private final DeviceRepository devices;

    public PilotService(PilotParticipantRepository participants, DeviceRepository devices) {
        this.participants = participants;
        this.devices = devices;
    }

    public record Registered(String participantId, String alias, String deviceId, OffsetDateTime consentAt,
            OffsetDateTime createdAt) {
    }

    @Transactional
    public Registered register(String alias, String devicePublicId, OffsetDateTime consentAt) {
        Device device = devices.findWithModelByPublicId(devicePublicId)
                .orElseThrow(() -> DomainException.notFound("기기"));
        OffsetDateTime now = Times.now();
        if (consentAt.isAfter(now.plusMinutes(5))) {
            throw DomainException.invalid("consentAt", "동의 시각은 지금보다 뒤일 수 없습니다.");
        }
        if (participants.existsByAlias(alias)) {
            throw new DomainException(ErrorCode.PILOT_ALIAS_EXISTS, "이미 쓰는 별칭입니다: " + alias);
        }
        PilotParticipant saved;
        try {
            saved = participants.saveAndFlush(
                    new PilotParticipant(PublicIds.next(), alias, device.getId(), Times.utc(consentAt), now));
        } catch (DataIntegrityViolationException race) {
            // 같은 별칭을 동시에 등록하면 UNIQUE가 하나만 남긴다
            throw new DomainException(ErrorCode.PILOT_ALIAS_EXISTS, "이미 쓰는 별칭입니다: " + alias);
        }
        return new Registered(saved.getPublicId(), saved.getAlias(), device.getPublicId(), saved.getConsentAt(),
                saved.getCreatedAt());
    }
}
