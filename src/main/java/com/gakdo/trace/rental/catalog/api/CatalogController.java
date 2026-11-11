package com.gakdo.trace.rental.catalog.api;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.gakdo.trace.rental.catalog.application.CatalogService;
import com.gakdo.trace.rental.catalog.application.CatalogService.CatalogDevice;
import com.gakdo.trace.rental.devicemodel.domain.ModelSpec;

/** GET /api/v1/catalog/devices — 로그인 없는 공개 보유 기기 목록(contracts listCatalogDevices). */
@RestController
public class CatalogController {

    private final CatalogService service;

    public CatalogController(CatalogService service) {
        this.service = service;
    }

    public record CatalogDeviceListResponse(List<CatalogDeviceResponse> items) {
    }

    public record CatalogDeviceResponse(String deviceId, String modelCode, String modelName, String manufacturer,
            String os, String color, String status, boolean available, long rentalCount, ModelSpec spec,
            DimensionsResponse dimensions, List<CatalogCheckpointResponse> checkpoints) {
    }

    /** 계약 ModelDimensions는 number라 BigDecimal 그대로 숫자로 낸다. */
    public record DimensionsResponse(BigDecimal widthMm, BigDecimal depthMm, BigDecimal baseHMm, BigDecimal lidHMm) {
    }

    public record CatalogCheckpointResponse(String checkpointId, String type, String status, OffsetDateTime occurredAt) {
    }

    @GetMapping("/api/v1/catalog/devices")
    public CatalogDeviceListResponse list() {
        return new CatalogDeviceListResponse(service.list().stream().map(CatalogController::toResponse).toList());
    }

    private static CatalogDeviceResponse toResponse(CatalogDevice d) {
        return new CatalogDeviceResponse(d.deviceId(), d.modelCode(), d.modelName(), d.manufacturer(), d.os(),
                d.color(), d.status(), d.available(), d.rentalCount(), d.spec(),
                new DimensionsResponse(d.dimensions().widthMm(), d.dimensions().depthMm(), d.dimensions().baseHMm(),
                        d.dimensions().lidHMm()),
                d.checkpoints().stream()
                        .map(c -> new CatalogCheckpointResponse(c.checkpointId(), c.type(), c.status(), c.occurredAt()))
                        .toList());
    }
}
