package com.gakdo.trace.rental.device.api;

import java.time.OffsetDateTime;
import java.util.List;

import com.gakdo.trace.rental.device.domain.Device;
import com.gakdo.trace.rental.device.domain.DeviceOs;
import com.gakdo.trace.rental.device.domain.DeviceStatus;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** 계약 rental-server.yaml의 기기 스키마(DeviceCreateRequest·Device·DevicePage). */
public final class DeviceDtos {

    private DeviceDtos() {
    }

    public record DeviceCreateRequest(
            @NotBlank @Size(max = 64) String serial,
            @NotNull @Pattern(regexp = "^[A-Z0-9][A-Z0-9-]{1,63}$") String modelCode,
            @NotNull DeviceOs os,
            @Size(max = 32) String color,
            @Size(max = 64) String assetNo) {
    }

    public record DeviceResponse(String deviceId, String serial, String modelCode, String modelName, DeviceOs os,
            String color, String assetNo, DeviceStatus status, OffsetDateTime retiredAt, OffsetDateTime createdAt,
            OffsetDateTime updatedAt) {

        static DeviceResponse from(Device d) {
            return new DeviceResponse(d.getPublicId(), d.getSerial(), d.getModel().getModelCode(), d.getModel().getName(),
                    d.getOs(), d.getColor(), d.getAssetNo(), d.getStatus(), d.getRetiredAt(), d.getCreatedAt(),
                    d.getUpdatedAt());
        }
    }

    /** totalCount(11/10): 같은 조건의 전체 건수 */
    public record DevicePageResponse(List<DeviceResponse> items, String nextCursor, long totalCount) {
    }
}
