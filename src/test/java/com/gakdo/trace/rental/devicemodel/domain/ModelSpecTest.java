// 이 파일이 확인하는 것: 기종 사양(CPU·RAM 등) 읽기의 하위 호환.

package com.gakdo.trace.rental.devicemodel.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

class ModelSpecTest {

    // [상황] DB에 저장된 사양에 이 버전이 모르는 칸(npuTops)이 있다(새 버전 배포 뒤 옛 버전으로 되돌린 경우).
    // [기대] 모르는 칸은 무시하고 나머지를 읽는다(되돌려도 서버가 깨지지 않게).
    // (도구) @Test = JUnit에게 "이 메서드가 테스트 하나"라고 알리는 표시. @DisplayName = 결과 화면에 보일 한글 이름
    @Test
    @DisplayName("저장된 사양에 모르는 키가 있어도 읽는다(계약 추가 뒤 이전 이미지로 되돌릴 때)")
    void ignoresUnknownKeys() throws Exception {
        // Hibernate jsonb 매퍼처럼 기본 설정(FAIL_ON_UNKNOWN_PROPERTIES=true) ObjectMapper로 읽는다
        // 준비+실행: 모르는 칸 npuTops가 섞인 사양 JSON 글자를 ModelSpec 객체로 읽는다 (ObjectMapper = JSON ↔ 자바 객체 변환기)
        ModelSpec spec = new ObjectMapper().readValue(
                "{\"cpu\":\"Apple M2\",\"ramGb\":8,\"gpu\":\"x\",\"npuTops\":18}", ModelSpec.class);
        // (도구) assertThat(실제값).isEqualTo(기대값) = "실제값이 기대값과 같아야 한다"는 검사. 다르면 테스트 실패
        // 확인: 아는 칸 cpu는 제대로 읽혀 "Apple M2"여야 한다
        assertThat(spec.cpu()).isEqualTo("Apple M2");
        // 확인: 아는 칸 gpu도 "x"로 읽혀야 한다 (모르는 칸 때문에 읽기가 실패하지 않았다는 뜻)
        assertThat(spec.gpu()).isEqualTo("x");
    }
}
