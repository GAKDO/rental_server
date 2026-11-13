package com.gakdo.trace.rental.rental.domain;

import java.time.LocalDate;
import java.time.ZoneId;

import com.gakdo.trace.rental.common.domain.DomainException;

/**
 * 렌탈 고객 이름·반납 예정일·메모 바꾸기(11/10, contracts #23-2).
 * 칸이 null이면 그대로 두고, Change의 값이 null이면 그 칸을 비운다(PATCH의 생략과 null을 나눈다).
 */
public record RentalDetails(Change<String> customerName, Change<LocalDate> dueAt, Change<String> note) {

    public static final int CUSTOMER_NAME_MAX = 64;
    public static final int NOTE_MAX = 500;

    public static final RentalDetails NONE = new RentalDetails(null, null, null);

    /** 반납 예정일의 '오늘'은 한국 시간으로 본다(관리자·고객 모두 한국). */
    public static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

    /** 한국 시간 기준 오늘 */
    public static LocalDate today() {
        return LocalDate.now(ZONE);
    }

    /**
     * 반납 예정일을 오늘보다 과거로 넣지 못하게 막는다(11/13 민서 요청). 오늘은 허용하고, 비우기(null)와 생략은 검사하지 않는다.
     * 이미 저장된 과거 날짜는 건드리지 않는다(다른 칸만 고칠 때는 dueAt이 생략되므로 통과).
     */
    public void requireDueNotBefore(LocalDate today) {
        if (dueAt != null && dueAt.value() != null && dueAt.value().isBefore(today)) {
            throw DomainException.invalid("dueAt", "반납 예정일은 오늘(" + today + ") 이후 날짜여야 합니다");
        }
    }

    /** 렌탈 생성 때 받은 값. 빈 값(공백만)은 없음으로 본다. */
    public static RentalDetails of(String customerName, LocalDate dueAt, String note) {
        return new RentalDetails(Change.text(customerName), new Change<>(dueAt), Change.text(note));
    }

    public boolean isEmpty() {
        return customerName == null && dueAt == null && note == null;
    }

    public record Change<T>(T value) {

        /** 앞뒤 공백을 지우고, 비면 null(비우기) */
        public static Change<String> text(String raw) {
            if (raw == null) {
                return new Change<>(null);
            }
            String trimmed = raw.strip();
            return new Change<>(trimmed.isEmpty() ? null : trimmed);
        }
    }
}
