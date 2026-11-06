package com.gakdo.trace.rental.pilot.api;

import java.time.OffsetDateTime;

import com.gakdo.trace.rental.pilot.application.PilotService;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/** 계약 rental-server.yaml PilotParticipantCreateRequest·PilotParticipant. */
public final class PilotDtos {

    private PilotDtos() {
    }

    public record PilotParticipantCreateRequest(
            @NotNull @Pattern(regexp = "^P\\d{2,3}$") String alias,
            @NotNull @Pattern(regexp = "^[A-Za-z0-9_-]{21}$") String deviceId,
            @NotNull OffsetDateTime consentAt) {
    }

    public record PilotParticipantResponse(String participantId, String alias, String deviceId,
            OffsetDateTime consentAt, OffsetDateTime createdAt) {

        static PilotParticipantResponse from(PilotService.Registered r) {
            return new PilotParticipantResponse(r.participantId(), r.alias(), r.deviceId(), r.consentAt(),
                    r.createdAt());
        }
    }
}
