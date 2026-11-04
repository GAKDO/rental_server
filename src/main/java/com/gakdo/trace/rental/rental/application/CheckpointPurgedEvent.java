package com.gakdo.trace.rental.rental.application;

import java.time.OffsetDateTime;

/**
 * trace.checkpoint.purged (contracts events/trace.checkpoint.purged.schema.json 1.0).
 * 시험 렌탈(시리얼 TEST-)을 지울 때 시점마다 하나씩 낸다. capture·report·diagnostics가 자기 데이터를 지운다.
 */
public record CheckpointPurgedEvent(
        String schemaVersion,
        String eventId,
        OffsetDateTime occurredAt,
        String rentalId,
        String checkpointId,
        String checkpointType,
        String deviceId,
        String serial) {

    public static final String ROUTING_KEY = "trace.checkpoint.purged";
    public static final String SCHEMA_VERSION = "1.0";
}
