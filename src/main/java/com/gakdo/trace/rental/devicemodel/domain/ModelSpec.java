package com.gakdo.trace.rental.devicemodel.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

/**
 * 기준 사양(진단 비교용). DB jsonb, 응답에서 비어 있는 키는 뺀다(계약 cpu·gpu는 string, null 아님).
 * gpu는 선택 — 외장 그래픽이 있는 Windows 노트북용(contracts 1.0.x 추가 필드).
 * 모르는 키는 무시한다: 계약에 키가 더해진 뒤 이전 이미지로 되돌려도 jsonb 행을 읽다 500이 나지 않게.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ModelSpec(
        @Size(max = 128) String cpu,
        @Min(1) Integer ramGb,
        @Min(1) Integer ssdGb,
        @Size(max = 128) String gpu) {
}
