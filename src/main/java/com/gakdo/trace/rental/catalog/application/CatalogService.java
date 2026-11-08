package com.gakdo.trace.rental.catalog.application;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gakdo.trace.rental.catalog.application.CatalogStore.CheckpointRow;
import com.gakdo.trace.rental.catalog.application.CatalogStore.DeviceRow;
import com.gakdo.trace.rental.devicemodel.domain.Dimensions;
import com.gakdo.trace.rental.devicemodel.domain.ModelSpec;

/**
 * 공개 보유 기기 목록(contracts listCatalogDevices, 11/8 민서 요청).
 * 흠집·진단은 다른 서버에 있으므로 여기서는 기기와 "검사가 끝난 시점 ID"만 주고, 화면이 그 ID로 capture·diagnostics를 모은다.
 */
@Service
public class CatalogService {

    /** 계약 CatalogDeviceList.maxItems */
    public static final int MAX_DEVICES = 200;
    /** 계약 CatalogDevice.checkpoints.maxItems */
    public static final int MAX_CHECKPOINTS = 12;

    private final CatalogStore store;
    private final ObjectMapper objectMapper;

    public CatalogService(CatalogStore store, ObjectMapper objectMapper) {
        this.store = store;
        this.objectMapper = objectMapper;
    }

    public record CatalogDevice(String deviceId, String modelCode, String modelName, String manufacturer, String os,
            String color, String status, boolean available, long rentalCount, ModelSpec spec, Dimensions dimensions,
            List<CheckpointRow> checkpoints) {
    }

    @Transactional(readOnly = true)
    public List<CatalogDevice> list() {
        List<DeviceRow> devices = store.devices(MAX_DEVICES);
        if (devices.isEmpty()) {
            return List.of();
        }
        Map<Long, List<CheckpointRow>> byDevice = new HashMap<>();
        for (CheckpointRow c : store.finishedCheckpoints(devices.stream().map(DeviceRow::id).toList())) {
            List<CheckpointRow> list = byDevice.computeIfAbsent(c.deviceId(), k -> new ArrayList<>());
            if (list.size() < MAX_CHECKPOINTS) {
                list.add(c);
            }
        }
        return devices.stream().map(d -> toView(d, byDevice.getOrDefault(d.id(), List.of()))).toList();
    }

    private CatalogDevice toView(DeviceRow d, List<CheckpointRow> checkpoints) {
        // 대여 가능: 운영 중(ACTIVE)이고 진행 중 렌탈이 없을 때. 정비 중(MAINTENANCE)은 목록에는 보이되 빌릴 수 없다
        boolean available = "ACTIVE".equals(d.status()) && !d.hasOpenRental();
        Dimensions dims = new Dimensions(d.widthMm(), d.depthMm(), d.baseHMm(), d.lidHMm());
        return new CatalogDevice(d.deviceId(), d.modelCode(), d.modelName(), d.manufacturer(), d.os(), d.color(),
                d.status(), available, d.rentalCount(), spec(d.specJson()), dims, checkpoints);
    }

    /** 사양 jsonb를 읽지 못하면 목록 전체를 실패시키지 않고 그 기기만 사양 없음(null)으로 둔다. */
    ModelSpec spec(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, ModelSpec.class);
        } catch (JsonProcessingException e) {
            return null;
        }
    }
}
