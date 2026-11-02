package com.gakdo.trace.rental.device.api;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.gakdo.trace.rental.common.domain.DomainException;
import com.gakdo.trace.rental.common.domain.DomainException.FieldError;
import com.gakdo.trace.rental.common.domain.ErrorCode;
import com.gakdo.trace.rental.device.domain.DeviceChanges;
import com.gakdo.trace.rental.device.domain.DeviceOs;
import com.gakdo.trace.rental.device.domain.DeviceStatus;

/**
 * PATCH 본문(DeviceUpdateRequest)을 읽는다. 생략(그대로)과 null(지우기)을 나누려고 JsonNode로 받는다.
 * serial·modelCode는 등록 뒤 바꿀 수 없어 보내면 400이다.
 */
final class DeviceUpdateParser {

    private static final Set<String> ALLOWED = Set.of("color", "assetNo", "os", "status");
    private static final Map<String, Integer> MAX_LENGTH = Map.of("color", 32, "assetNo", 64);

    private DeviceUpdateParser() {
    }

    static DeviceChanges parse(JsonNode body) {
        if (body == null || !body.isObject() || body.isEmpty()) {
            throw DomainException.invalid("body", "바꿀 값을 하나 이상 보내야 합니다");
        }
        List<FieldError> errors = new ArrayList<>();
        for (Iterator<String> it = body.fieldNames(); it.hasNext();) {
            String name = it.next();
            if (!ALLOWED.contains(name)) {
                errors.add(new FieldError(name, "serial".equals(name) || "modelCode".equals(name)
                        ? "등록 뒤에는 바꿀 수 없습니다" : "받지 않는 필드입니다"));
            }
        }
        Optional<String> color = text(body, "color", errors);
        Optional<String> assetNo = text(body, "assetNo", errors);
        DeviceOs os = code(body, "os", DeviceOs.class, errors);
        DeviceStatus status = code(body, "status", DeviceStatus.class, errors);
        if (!errors.isEmpty()) {
            throw new DomainException(ErrorCode.VALIDATION_FAILED, "요청 본문이 올바르지 않습니다.", errors);
        }
        return new DeviceChanges(color, assetNo, os, status);
    }

    /** 없으면 null(그대로), null이면 empty(지우기). */
    private static Optional<String> text(JsonNode body, String field, List<FieldError> errors) {
        if (!body.has(field)) {
            return null;
        }
        JsonNode node = body.get(field);
        if (node.isNull()) {
            return Optional.empty();
        }
        if (!node.isTextual()) {
            errors.add(new FieldError(field, "문자열이어야 합니다"));
            return null;
        }
        if (node.asText().length() > MAX_LENGTH.get(field)) {
            errors.add(new FieldError(field, MAX_LENGTH.get(field) + "자 이하여야 합니다"));
            return null;
        }
        return Optional.of(node.asText());
    }

    private static <E extends Enum<E>> E code(JsonNode body, String field, Class<E> type, List<FieldError> errors) {
        if (!body.has(field)) {
            return null;
        }
        JsonNode node = body.get(field);
        try {
            if (!node.isTextual()) {
                throw new IllegalArgumentException();
            }
            return Enum.valueOf(type, node.asText());
        } catch (IllegalArgumentException e) {
            errors.add(new FieldError(field, "허용하지 않는 값입니다"));
            return null;
        }
    }
}
