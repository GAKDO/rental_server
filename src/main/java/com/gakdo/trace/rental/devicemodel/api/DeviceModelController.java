package com.gakdo.trace.rental.devicemodel.api;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.gakdo.trace.rental.common.infra.CurrentUser;
import com.gakdo.trace.rental.devicemodel.api.DeviceModelDtos.DeviceModelListResponse;
import com.gakdo.trace.rental.devicemodel.api.DeviceModelDtos.DeviceModelResponse;
import com.gakdo.trace.rental.devicemodel.api.DeviceModelDtos.DeviceModelSummaryResponse;
import com.gakdo.trace.rental.devicemodel.api.DeviceModelDtos.DeviceModelUpsertRequest;
import com.gakdo.trace.rental.devicemodel.application.DeviceModelService;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;

/** /api/v1/device-models — 기종과 3D 트윈 치수 (FR-RNT-03). 권한은 SecurityConfig. */
@RestController
@RequestMapping("/api/v1/device-models")
public class DeviceModelController {

    /** 계약 ModelCode 패턴 */
    static final String MODEL_CODE = "^[A-Z0-9][A-Z0-9-]{1,63}$";

    private final DeviceModelService service;

    public DeviceModelController(DeviceModelService service) {
        this.service = service;
    }

    @GetMapping
    public DeviceModelListResponse list() {
        return new DeviceModelListResponse(service.list().stream().map(DeviceModelSummaryResponse::from).toList());
    }

    @GetMapping("/{modelCode}")
    public DeviceModelResponse get(@PathVariable @Pattern(regexp = MODEL_CODE) String modelCode) {
        return DeviceModelResponse.from(service.get(modelCode));
    }

    @PutMapping("/{modelCode}")
    public DeviceModelResponse upsert(@PathVariable @Pattern(regexp = MODEL_CODE) String modelCode,
            @Valid @RequestBody DeviceModelUpsertRequest request, @AuthenticationPrincipal Jwt jwt) {
        return DeviceModelResponse.from(
                service.upsert(modelCode, request.toAttributes(), CurrentUser.from(jwt).accountId()));
    }
}
