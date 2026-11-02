package com.gakdo.trace.rental.common.domain;

import org.springframework.http.HttpStatus;

/**
 * 이 서버가 내는 오류 code. 값은 contracts codes.yaml {@code error_code}와 같아야 한다(복사가 아니라 이름 대조).
 * 제목은 화면이 그대로 보여 줄 수 있는 한국어 짧은 문장이다.
 */
public enum ErrorCode {
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "요청 형식이 올바르지 않습니다"),
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다"),
    FORBIDDEN(HttpStatus.FORBIDDEN, "권한이 없습니다"),
    OUT_OF_SCOPE(HttpStatus.FORBIDDEN, "접근할 수 없는 렌탈입니다"),
    NOT_FOUND(HttpStatus.NOT_FOUND, "찾을 수 없습니다"),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버 오류가 발생했습니다"),
    RENTAL_OPEN_EXISTS(HttpStatus.CONFLICT, "진행 중인 렌탈이 있습니다"),
    DEVICE_NOT_ACTIVE(HttpStatus.CONFLICT, "렌탈할 수 없는 기기입니다"),
    CHECKPOINT_ORDER_VIOLATION(HttpStatus.CONFLICT, "시점 순서가 맞지 않습니다"),
    RENTAL_NOT_CLOSABLE(HttpStatus.CONFLICT, "아직 종료할 수 없는 렌탈입니다"),
    DEVICE_SERIAL_EXISTS(HttpStatus.CONFLICT, "이미 등록된 시리얼입니다"),
    DEVICE_HAS_OPEN_RENTAL(HttpStatus.CONFLICT, "진행 중인 렌탈이 있는 기기입니다");

    private final HttpStatus status;
    private final String title;

    ErrorCode(HttpStatus status, String title) {
        this.status = status;
        this.title = title;
    }

    public HttpStatus status() {
        return status;
    }

    public String title() {
        return title;
    }
}
