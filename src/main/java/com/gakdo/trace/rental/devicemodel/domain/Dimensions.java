package com.gakdo.trace.rental.devicemodel.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 3D 트윈 치수(mm). DB numeric(6,1)·(5,1)에 맞춰 소수 한 자리로 반올림해 저장한다.
 * 상한은 계약 1.0.0 ModelDimensions·촬영 세트 CaptureDeviceDimensions와 같다(가로·세로 1000, 두께 100).
 * 더 큰 값을 받으면 그 기종의 촬영 세트가 capture-server에서 모두 400이 되므로 여기서 먼저 막는다.
 */
public record Dimensions(
        @NotNull @Positive @DecimalMax("1000") BigDecimal widthMm,
        @NotNull @Positive @DecimalMax("1000") BigDecimal depthMm,
        @NotNull @Positive @DecimalMax("100") BigDecimal baseHMm,
        @NotNull @Positive @DecimalMax("100") BigDecimal lidHMm) {

    public Dimensions rounded() {
        return new Dimensions(round(widthMm), round(depthMm), round(baseHMm), round(lidHMm));
    }

    private static BigDecimal round(BigDecimal value) {
        return value.setScale(1, RoundingMode.HALF_UP);
    }
}
