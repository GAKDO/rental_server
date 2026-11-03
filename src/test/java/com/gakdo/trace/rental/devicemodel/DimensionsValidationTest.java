package com.gakdo.trace.rental.devicemodel;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.Set;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.gakdo.trace.rental.devicemodel.domain.Dimensions;

class DimensionsValidationTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        factory.close();
    }

    private static Dimensions dims(String w, String d, String base, String lid) {
        return new Dimensions(new BigDecimal(w), new BigDecimal(d), new BigDecimal(base), new BigDecimal(lid));
    }

    private static Set<String> invalidFields(Dimensions dimensions) {
        return validator.validate(dimensions).stream()
                .map(ConstraintViolation::getPropertyPath)
                .map(Object::toString)
                .collect(java.util.stream.Collectors.toSet());
    }

    @Test
    @DisplayName("촬영 세트 상한(가로·세로 1000 mm, 두께 100 mm)까지의 치수는 받는다")
    void acceptsDimensionsUpToCaptureLimits() {
        assertThat(invalidFields(dims("304.1", "215.0", "7.0", "4.5"))).isEmpty();
        assertThat(invalidFields(dims("1000", "1000", "100", "100"))).isEmpty();
    }

    @Test
    @DisplayName("촬영 세트 상한을 넘는 치수는 필드마다 거절한다")
    void rejectsDimensionsAboveCaptureLimits() {
        assertThat(invalidFields(dims("1000.1", "1000.1", "100.1", "100.1")))
                .containsExactlyInAnyOrder("widthMm", "depthMm", "baseHMm", "lidHMm");
    }
}
