package com.gakdo.trace.rental.devicemodel.api;

import java.time.OffsetDateTime;
import java.util.List;

import com.gakdo.trace.rental.devicemodel.domain.DeviceModel;
import com.gakdo.trace.rental.devicemodel.domain.Dimensions;
import com.gakdo.trace.rental.devicemodel.domain.FormFactor;
import com.gakdo.trace.rental.devicemodel.domain.ModelSpec;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** 계약 rental-server.yaml의 기종 스키마(DeviceModelUpsertRequest·DeviceModel·DeviceModelSummary). */
public final class DeviceModelDtos {

    private DeviceModelDtos() {
    }

    public record DeviceModelUpsertRequest(
            @NotBlank @Size(max = 128) String name,
            @Size(max = 64) String manufacturer,
            @NotNull FormFactor formFactor,
            @Valid ModelSpec spec,
            @NotNull @Valid Dimensions dimensions) {

        DeviceModel.Attributes toAttributes() {
            return new DeviceModel.Attributes(name, manufacturer, formFactor, spec, dimensions);
        }
    }

    public record DeviceModelResponse(String modelCode, String name, String manufacturer, FormFactor formFactor,
            ModelSpec spec, Dimensions dimensions, OffsetDateTime updatedAt) {

        static DeviceModelResponse from(DeviceModel m) {
            return new DeviceModelResponse(m.getModelCode(), m.getName(), m.getManufacturer(), m.getFormFactor(),
                    m.getSpec(), m.getDimensions(), m.getUpdatedAt());
        }
    }

    /** 목록 항목. spec은 목록에서 기준 사양을 한눈에 보려고 항상 싣는다(없으면 null). */
    public record DeviceModelSummaryResponse(String modelCode, String name, String manufacturer,
            FormFactor formFactor, ModelSpec spec) {

        static DeviceModelSummaryResponse from(DeviceModel m) {
            return new DeviceModelSummaryResponse(m.getModelCode(), m.getName(), m.getManufacturer(),
                    m.getFormFactor(), m.getSpec());
        }
    }

    public record DeviceModelListResponse(List<DeviceModelSummaryResponse> items) {
    }
}
