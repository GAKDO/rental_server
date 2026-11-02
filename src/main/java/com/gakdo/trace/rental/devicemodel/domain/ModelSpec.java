package com.gakdo.trace.rental.devicemodel.domain;

import com.fasterxml.jackson.annotation.JsonInclude;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

/** 기준 사양(진단 비교용). DB jsonb, 응답에서 비어 있는 키는 뺀다(계약 cpu는 string, null 아님). */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ModelSpec(
        @Size(max = 128) String cpu,
        @Min(1) Integer ramGb,
        @Min(1) Integer ssdGb) {
}
