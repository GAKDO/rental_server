package com.gakdo.trace.rental.devicemodel.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

class ModelSpecTest {

    @Test
    @DisplayName("저장된 사양에 모르는 키가 있어도 읽는다(계약 추가 뒤 이전 이미지로 되돌릴 때)")
    void ignoresUnknownKeys() throws Exception {
        // Hibernate jsonb 매퍼처럼 기본 설정(FAIL_ON_UNKNOWN_PROPERTIES=true) ObjectMapper로 읽는다
        ModelSpec spec = new ObjectMapper().readValue(
                "{\"cpu\":\"Apple M2\",\"ramGb\":8,\"gpu\":\"x\",\"npuTops\":18}", ModelSpec.class);
        assertThat(spec.cpu()).isEqualTo("Apple M2");
        assertThat(spec.gpu()).isEqualTo("x");
    }
}
