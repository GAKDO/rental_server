package com.gakdo.trace.rental.device.domain;

import java.util.Optional;

/**
 * PATCH로 바꿀 값. 필드가 null이면 그대로 두고, Optional.empty()면 값을 지운다(color·assetNo만).
 * 시리얼·기종은 여기 없다: 등록 뒤 바꿀 수 없다.
 */
public record DeviceChanges(
        Optional<String> color,
        Optional<String> assetNo,
        DeviceOs os,
        DeviceStatus status) {
}
