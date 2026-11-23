// 이 파일이 확인하는 것: 기종 치수 허용 범위. 부스 촬영 세트에 들어갈 수 있는 크기까지만 받는다.

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

    // [상황] 맥북 에어 치수와, 딱 상한(가로·세로 1000mm, 두께 100mm) 치수를 넣는다.
    // [기대] 둘 다 받는다.
    @Test
    @DisplayName("촬영 세트 상한(가로·세로 1000 mm, 두께 100 mm)까지의 치수는 받는다")
    void acceptsDimensionsUpToCaptureLimits() {
        assertThat(invalidFields(dims("304.1", "215.0", "7.0", "4.5"))).isEmpty();
        assertThat(invalidFields(dims("1000", "1000", "100", "100"))).isEmpty();
    }

    // [상황] 상한을 0.1mm씩 넘긴 치수를 넣는다.
    // [기대] 가로·세로·본체 두께·덮개 두께 칸이 각각 오류로 나온다.
    @Test
    @DisplayName("촬영 세트 상한을 넘는 치수는 필드마다 거절한다")
    void rejectsDimensionsAboveCaptureLimits() {
        assertThat(invalidFields(dims("1000.1", "1000.1", "100.1", "100.1")))
                .containsExactlyInAnyOrder("widthMm", "depthMm", "baseHMm", "lidHMm");
    }
}
