package com.gakdo.trace.rental.device.api;


import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.databind.JsonNode;
import com.gakdo.trace.rental.common.infra.Cursor;
import com.gakdo.trace.rental.common.infra.CurrentUser;
import com.gakdo.trace.rental.common.infra.PublicIds;
import com.gakdo.trace.rental.device.api.DeviceDtos.DeviceCreateRequest;
import com.gakdo.trace.rental.device.api.DeviceDtos.DevicePageResponse;
import com.gakdo.trace.rental.device.api.DeviceDtos.DeviceResponse;
import com.gakdo.trace.rental.device.application.DeviceService;
import com.gakdo.trace.rental.device.domain.DeviceStatus;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** /api/v1/devices — 기기 등록·검색·상세·수정 (FR-RNT-01, FR-RNT-05). ADMIN만(SecurityConfig). */
@RestController
@RequestMapping("/api/v1/devices")
public class DeviceController {

    private final DeviceService service;

    public DeviceController(DeviceService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public DeviceResponse register(@Valid @RequestBody DeviceCreateRequest request, @AuthenticationPrincipal Jwt jwt) {
        return DeviceResponse.from(service.register(request.serial(), request.modelCode(), request.os(),
                request.color(), request.assetNo(), CurrentUser.from(jwt).accountId()));
    }

    @GetMapping
    public DevicePageResponse search(
            @RequestParam(required = false) @Pattern(regexp = "^[A-Z0-9][A-Z0-9-]{1,63}$") String modelCode,
            @RequestParam(required = false) DeviceStatus status,
            @RequestParam(required = false) @Size(min = 1, max = 64) String q,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit,
            @RequestParam(required = false) String cursor) {
        DeviceService.Page page = service.search(modelCode, status, q, Cursor.decode(cursor), limit);
        return new DevicePageResponse(page.items().stream().map(DeviceResponse::from).toList(), page.nextCursor(),
                page.totalCount());
    }

    @GetMapping("/{deviceId}")
    public DeviceResponse get(@PathVariable @Pattern(regexp = PublicIds.PATTERN) String deviceId) {
        return DeviceResponse.from(service.get(deviceId));
    }

    @PatchMapping("/{deviceId}")
    public DeviceResponse change(@PathVariable @Pattern(regexp = PublicIds.PATTERN) String deviceId, @RequestBody JsonNode body,
            @AuthenticationPrincipal Jwt jwt) {
        return DeviceResponse.from(
                service.change(deviceId, DeviceUpdateParser.parse(body), CurrentUser.from(jwt).accountId()));
    }

    /** 기기 지우기(11/10). 이력 없는 기기·TEST- 시험 기기만, 아니면 409 DEVICE_IN_USE. ADMIN만(SecurityConfig). */
    @DeleteMapping("/{deviceId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable @Pattern(regexp = PublicIds.PATTERN) String deviceId) {
        service.delete(deviceId);
    }
}
