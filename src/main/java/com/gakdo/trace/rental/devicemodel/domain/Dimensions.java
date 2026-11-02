package com.gakdo.trace.rental.devicemodel.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/** 3D 트윈 치수(mm). DB numeric(6,1)·(5,1)에 맞춰 소수 한 자리로 반올림해 저장한다. */
public record Dimensions(
        @NotNull @Positive @DecimalMax("99999.9") BigDecimal widthMm,
        @NotNull @Positive @DecimalMax("99999.9") BigDecimal depthMm,
        @NotNull @Positive @DecimalMax("9999.9") BigDecimal baseHMm,
        @NotNull @Positive @DecimalMax("9999.9") BigDecimal lidHMm) {

    public Dimensions rounded() {
        return new Dimensions(round(widthMm), round(depthMm), round(baseHMm), round(lidHMm));
    }

    private static BigDecimal round(BigDecimal value) {
        return value.setScale(1, RoundingMode.HALF_UP);
    }
}
