package com.gakdo.trace.rental.rental.domain;

import java.time.LocalDate;

/**
 * 렌탈 고객 이름·반납 예정일·메모 바꾸기(11/10, contracts #23-2).
 * 칸이 null이면 그대로 두고, Change의 값이 null이면 그 칸을 비운다(PATCH의 생략과 null을 나눈다).
 */
public record RentalDetails(Change<String> customerName, Change<LocalDate> dueAt, Change<String> note) {

    public static final int CUSTOMER_NAME_MAX = 64;
    public static final int NOTE_MAX = 500;

    public static final RentalDetails NONE = new RentalDetails(null, null, null);

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
