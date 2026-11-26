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

    // 검사기를 만드는 공장. 테스트가 끝나면 닫아야 해서 따로 보관한다
    private static ValidatorFactory factory;
    // 치수 객체에 붙은 규칙(@Max 등)을 검사하는 검사기 (Bean Validation)
    private static Validator validator;

    // (도구) @BeforeAll = 이 파일의 테스트들이 시작되기 전에 딱 한 번 실행한다
    @BeforeAll
    static void setUp() {
        // 준비: 기본 설정의 검사기 공장을 만든다
        factory = Validation.buildDefaultValidatorFactory();
        // 준비: 공장에서 검사기를 하나 꺼내 모든 테스트가 같이 쓴다
        validator = factory.getValidator();
    }

    // (도구) @AfterAll = 이 파일의 테스트가 모두 끝난 뒤 딱 한 번 실행한다
    @AfterAll
    static void tearDown() {
        // 정리: 검사기 공장을 닫아 자원을 돌려준다
        factory.close();
    }

    // 도우미: 가로·세로·본체 두께·덮개 두께를 글자로 받아 치수 객체를 만든다 (BigDecimal = 소수를 정확히 다루는 숫자)
    private static Dimensions dims(String w, String d, String base, String lid) {
        // 네 값을 BigDecimal로 바꿔 치수 객체를 만든다
        return new Dimensions(new BigDecimal(w), new BigDecimal(d), new BigDecimal(base), new BigDecimal(lid));
    }

    // 도우미: 치수를 검사해서 규칙을 어긴 칸 이름들만 모아 돌려준다 (비어 있으면 통과)
    private static Set<String> invalidFields(Dimensions dimensions) {
        // 검사기로 치수를 검사해 규칙 위반 목록을 하나씩 흘려보낸다
        return validator.validate(dimensions).stream()
                // 위반마다 어느 칸(예: widthMm)에서 났는지 경로를 꺼낸다
                .map(ConstraintViolation::getPropertyPath)
                // 경로를 글자로 바꾼다
                .map(Object::toString)
                // 칸 이름들을 겹치지 않는 묶음(Set)으로 모은다
                .collect(java.util.stream.Collectors.toSet());
    }

    // [상황] 맥북 에어 치수와, 딱 상한(가로·세로 1000mm, 두께 100mm) 치수를 넣는다.
    // [기대] 둘 다 받는다.
    // (도구) @Test = JUnit에게 "이 메서드가 테스트 하나"라고 알리는 표시. @DisplayName = 결과 화면에 보일 한글 이름
    @Test
    @DisplayName("촬영 세트 상한(가로·세로 1000 mm, 두께 100 mm)까지의 치수는 받는다")
    void acceptsDimensionsUpToCaptureLimits() {
        // (도구) assertThat(실제값).isEmpty() = "실제값이 비어 있어야 한다"는 검사. 여기선 "어긴 칸이 하나도 없어야 한다"
        // 실행+확인: 실제 맥북 에어 치수는 규칙을 어긴 칸이 없어야 한다
        assertThat(invalidFields(dims("304.1", "215.0", "7.0", "4.5"))).isEmpty();
        // 실행+확인: 딱 상한값(가로·세로 1000, 두께 100)도 경계 포함이라 통과해야 한다
        assertThat(invalidFields(dims("1000", "1000", "100", "100"))).isEmpty();
    }

    // [상황] 상한을 0.1mm씩 넘긴 치수를 넣는다.
    // [기대] 가로·세로·본체 두께·덮개 두께 칸이 각각 오류로 나온다.
    @Test
    @DisplayName("촬영 세트 상한을 넘는 치수는 필드마다 거절한다")
    void rejectsDimensionsAboveCaptureLimits() {
        // 실행: 네 칸 모두 상한보다 0.1mm 큰 치수를 검사한다
        assertThat(invalidFields(dims("1000.1", "1000.1", "100.1", "100.1")))
                // 확인: 네 칸 이름이 순서 상관없이 정확히 모두 오류로 나와야 한다 (containsExactlyInAnyOrder = 순서 무관, 빠짐·더함 없이)
                .containsExactlyInAnyOrder("widthMm", "depthMm", "baseHMm", "lidHMm");
    }
}
