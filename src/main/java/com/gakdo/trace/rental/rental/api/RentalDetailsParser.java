package com.gakdo.trace.rental.rental.api;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.gakdo.trace.rental.common.domain.DomainException;
import com.gakdo.trace.rental.common.domain.DomainException.FieldError;
import com.gakdo.trace.rental.common.domain.ErrorCode;
import com.gakdo.trace.rental.rental.domain.RentalDetails;
import com.gakdo.trace.rental.rental.domain.RentalDetails.Change;

/**
 * PATCH 본문(RentalUpdateRequest)을 읽는다. 생략(그대로)과 null(비우기)을 나누려고 JsonNode로 받는다(DeviceUpdateParser와 같은 방식).
 * 고객 이름·메모는 앞뒤 공백을 지우고, 비면 비우기로 본다.
 */
final class RentalDetailsParser {

    private static final String CUSTOMER_NAME = "customerName";
    private static final String DUE_AT = "dueAt";
    private static final String NOTE = "note";
    private static final Set<String> ALLOWED = Set.of(CUSTOMER_NAME, DUE_AT, NOTE);

    private RentalDetailsParser() {
    }

    static RentalDetails parse(JsonNode body) {
        if (body == null || !body.isObject() || body.isEmpty()) {
            throw DomainException.invalid("body", "바꿀 값을 하나 이상 보내야 합니다");
        }
        List<FieldError> errors = new ArrayList<>();
        for (Iterator<String> it = body.fieldNames(); it.hasNext();) {
            String name = it.next();
            if (!ALLOWED.contains(name)) {
                errors.add(new FieldError(name, "받지 않는 필드입니다"));
            }
        }
        Change<String> customerName = text(body, CUSTOMER_NAME, RentalDetails.CUSTOMER_NAME_MAX, errors);
        Change<LocalDate> dueAt = date(body, errors);
        Change<String> note = text(body, NOTE, RentalDetails.NOTE_MAX, errors);
        if (!errors.isEmpty()) {
            throw new DomainException(ErrorCode.VALIDATION_FAILED, "요청 본문이 올바르지 않습니다.", errors);
        }
        return new RentalDetails(customerName, dueAt, note);
    }

    private static Change<String> text(JsonNode body, String field, int max, List<FieldError> errors) {
        if (!body.has(field)) {
            return null;
        }
        JsonNode node = body.get(field);
        if (node.isNull()) {
            return new Change<>(null);
        }
        if (!node.isTextual()) {
            errors.add(new FieldError(field, "문자열이어야 합니다"));
            return null;
        }
        Change<String> change = Change.text(node.asText());
        if (change.value() != null && change.value().length() > max) {
            errors.add(new FieldError(field, max + "자 이하여야 합니다"));
            return null;
        }
        return change;
    }

    private static Change<LocalDate> date(JsonNode body, List<FieldError> errors) {
        if (!body.has(DUE_AT)) {
            return null;
        }
        JsonNode node = body.get(DUE_AT);
        if (node.isNull()) {
            return new Change<>(null);
        }
        try {
            if (!node.isTextual()) {
                throw new DateTimeParseException("not text", node.toString(), 0);
            }
            return new Change<>(LocalDate.parse(node.asText()));
        } catch (DateTimeParseException e) {
            errors.add(new FieldError(DUE_AT, "날짜(YYYY-MM-DD)여야 합니다"));
            return null;
        }
    }
}
