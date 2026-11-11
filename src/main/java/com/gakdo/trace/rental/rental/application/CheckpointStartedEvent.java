package com.gakdo.trace.rental.rental.application;

import java.time.OffsetDateTime;

/**
 * trace.checkpoint.started (contracts events/trace.checkpoint.started.schema.json 1.1).
 * report-server가 리포트·검증 화면의 기종 이름과 시점 종류를 REST 없이 알게 한다.
 */
public record CheckpointStartedEvent(
        String schemaVersion,
        String eventId,
        OffsetDateTime occurredAt,
        String rentalId,
        String checkpointId,
        String checkpointType,
        String deviceId,
        String serial,
        String modelCode,
        String modelName) {

    public static final String ROUTING_KEY = "trace.checkpoint.started";
    public static final String SCHEMA_VERSION = "1.1";
}
