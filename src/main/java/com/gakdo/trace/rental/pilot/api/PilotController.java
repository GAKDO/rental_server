package com.gakdo.trace.rental.pilot.api;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.gakdo.trace.rental.pilot.api.PilotDtos.PilotParticipantCreateRequest;
import com.gakdo.trace.rental.pilot.api.PilotDtos.PilotParticipantResponse;
import com.gakdo.trace.rental.pilot.application.PilotService;

import jakarta.validation.Valid;

/** /api/v1/pilot/participants — 파일럿 별칭 등록(ADMIN만, SecurityConfig). */
@RestController
@RequestMapping("/api/v1/pilot/participants")
public class PilotController {

    private final PilotService service;

    public PilotController(PilotService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PilotParticipantResponse create(@Valid @RequestBody PilotParticipantCreateRequest request) {
        return PilotParticipantResponse.from(service.register(request.alias(), request.deviceId(),
                request.consentAt()));
    }
}
