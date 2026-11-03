package com.gakdo.trace.rental.device.domain;

/**
 * PATCH로 바꿀 값. 필드가 null이면 그대로 둔다. 시리얼·기종은 여기 없다: 등록 뒤 바꿀 수 없다.
 * color·assetNo는 "그대로·바꾸기·지우기" 셋을 나눠야 해서 TextChange로 받는다(Optional을 null로 쓰지 않으려고).
 */
public record DeviceChanges(
        TextChange color,
        TextChange assetNo,
        DeviceOs os,
        DeviceStatus status) {

    /** 바꿀 글자 값. value가 null이면 값을 지운다. */
    public record TextChange(String value) {

        public static TextChange of(String value) {
            return new TextChange(value);
        }

        public static TextChange clear() {
            return new TextChange(null);
        }
    }
}
