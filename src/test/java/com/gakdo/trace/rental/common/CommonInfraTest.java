// 이 파일이 확인하는 것: 여러 기능이 같이 쓰는 기본 도구(공개 ID, 목록 쪽 넘김 커서, 로그인 사용자 정보).

package com.gakdo.trace.rental.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

import com.gakdo.trace.rental.common.domain.DomainException;
import com.gakdo.trace.rental.common.domain.ErrorCode;
import com.gakdo.trace.rental.common.infra.Cursor;
import com.gakdo.trace.rental.common.infra.CurrentUser;
import com.gakdo.trace.rental.common.infra.PublicIds;
import com.gakdo.trace.rental.common.infra.Times;

class CommonInfraTest {

    // [상황] 공개 ID를 1만 개 만든다.
    // [기대] 모두 계약 모양(21자 NanoID)이고 하나도 겹치지 않는다. 짧은 값·빈 값은 틀린 ID.
    @Test
    @DisplayName("publicId는 계약 PublicId 패턴(NanoID 21자)이고 겹치지 않는다")
    void publicIds() {
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < 10_000; i++) {
            String id = PublicIds.next();
            assertThat(id).matches(PublicIds.PATTERN);
            ids.add(id);
        }
        assertThat(ids).hasSize(10_000);
        assertThat(PublicIds.isValid("too-short")).isFalse();
        assertThat(PublicIds.isValid(null)).isFalse();
    }

    // [상황] 목록 "다음 쪽" 커서를 글자로 바꿨다가 다시 읽는다.
    // [기대] 같은 값(생성 시각·공개 ID)으로 돌아오고 DB 내부 번호는 담지 않는다. 비었으면 첫 쪽.
    @Test
    @DisplayName("커서는 (createdAt, publicId)를 왕복하고 내부 순번은 싣지 않는다")
    void cursorRoundTrip() {
        Cursor cursor = new Cursor(Times.utc(OffsetDateTime.parse("2026-11-02T10:00:00.123456+09:00")),
                "IPv6mcQOlTkKj6mOecAQt");
        Cursor decoded = Cursor.decode(cursor.encode());
        assertThat(decoded).isEqualTo(cursor);
        assertThat(Cursor.decode(null)).isNull();
        assertThat(Cursor.decode(" ")).isNull();
    }

    // [상황] 깨진 커서를 넣는다.
    // [기대] 400 VALIDATION_FAILED.
    //        ※ 응답 코드: 400 = 요청 내용이 형식에 맞지 않음
    @Test
    @DisplayName("깨진 커서는 400 VALIDATION_FAILED")
    void brokenCursor() {
        assertThatThrownBy(() -> Cursor.decode("not-a-cursor"))
                .isInstanceOfSatisfying(DomainException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.VALIDATION_FAILED));
    }

    // [상황] 로그인 토큰에서 사용자 정보를 만든다(렌탈 목록에 틀린 값 "bad" 섞임). 부스 토큰도 넣어 본다.
    // [기대] 고객 계정·역할·볼 수 있는 렌탈을 읽고 틀린 값은 버린다. 자기 렌탈만 볼 수 있다. 부스는 계정 번호가 없다.
    @Test
    @DisplayName("CurrentUser는 sub·role·rentals 클레임에서 만들고 NanoID가 아닌 값은 버린다")
    void currentUser() {
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "none")
                .claims(c -> c.putAll(Map.of("sub", "cust00000000000000001", "role", "CUSTOMER",
                        "rentals", List.of("IPv6mcQOlTkKj6mOecAQt", "bad"))))
                .build();
        CurrentUser user = CurrentUser.from(jwt);
        assertThat(user.accountId()).isEqualTo("cust00000000000000001");
        assertThat(user.isAdmin()).isFalse();
        assertThat(user.rentals()).containsExactly("IPv6mcQOlTkKj6mOecAQt");
        assertThat(user.canSee("IPv6mcQOlTkKj6mOecAQt")).isTrue();
        assertThat(user.canSee("other0000000000000001")).isFalse();

        Jwt booth = Jwt.withTokenValue("t").header("alg", "none").claim("sub", "booth-01").claim("role", "BOOTH").build();
        assertThat(CurrentUser.from(booth).accountId()).isNull();
    }
}
