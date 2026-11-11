package com.gakdo.trace.rental.catalog.application;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 공개 기기 목록의 읽기 재료. 기기·기종·렌탈·시점을 한 번에 모으는 조회라 JPA 엔티티 대신 행을 그대로 받는다.
 * 시리얼·자산번호·대여자는 처음부터 읽지 않는다(공개 경로라 실수로 내보내지 않게).
 */
public interface CatalogStore {

    /** 폐기를 뺀 기기, 최근 등록 순으로 최대 limit대. */
    List<DeviceRow> devices(int limit);

    /** 기기들의 검사가 끝난 시점(GRADED·SEALED), 최근 것부터. */
    List<CheckpointRow> finishedCheckpoints(List<Long> deviceIds);

    record DeviceRow(long id, String deviceId, String os, String color, String status, String modelCode,
            String modelName, String manufacturer, String specJson, BigDecimal widthMm, BigDecimal depthMm,
            BigDecimal baseHMm, BigDecimal lidHMm, long rentalCount, boolean hasOpenRental) {
    }

    record CheckpointRow(long deviceId, String checkpointId, String type, String status, OffsetDateTime occurredAt) {
    }
}
