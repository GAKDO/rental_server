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
    // (도구) @Test = JUnit에게 "이 메서드가 테스트 하나"라고 알리는 표시. @DisplayName = 결과 화면에 보일 한글 이름
    @Test
    @DisplayName("publicId는 계약 PublicId 패턴(NanoID 21자)이고 겹치지 않는다")
    void publicIds() {
        // 준비: 만든 ID를 모을 묶음(Set)을 만든다. Set은 같은 값을 한 번만 담으므로 겹침을 셀 수 있다
        Set<String> ids = new HashSet<>();
        // 실행: 1만 번 되풀이한다
        for (int i = 0; i < 10_000; i++) {
            // 실행: 새 공개 ID를 하나 만든다
            String id = PublicIds.next();
            // (도구) assertThat(글자).matches(정규식) = 글자가 그 모양(정규식)에 맞아야 한다는 검사. 틀리면 테스트 실패
            // 확인: 만든 ID가 계약의 공개 ID 모양(21자 NanoID)에 맞아야 한다
            assertThat(id).matches(PublicIds.PATTERN);
            // 실행: 만든 ID를 묶음에 넣는다 (겹치면 개수가 늘지 않음)
            ids.add(id);
        }
        // 확인: 묶음에 1만 개가 다 들어 있어야 한다 = 하나도 겹치지 않았다
        assertThat(ids).hasSize(10_000);
        // 확인: 21자가 아닌 짧은 글자는 올바른 ID가 아니라고(false) 판단해야 한다
        assertThat(PublicIds.isValid("too-short")).isFalse();
        // 확인: 값이 없는(null) ID도 올바르지 않다고(false) 판단해야 한다
        assertThat(PublicIds.isValid(null)).isFalse();
    }

    // [상황] 목록 "다음 쪽" 커서를 글자로 바꿨다가 다시 읽는다.
    // [기대] 같은 값(생성 시각·공개 ID)으로 돌아오고 DB 내부 번호는 담지 않는다. 비었으면 첫 쪽.
    @Test
    @DisplayName("커서는 (createdAt, publicId)를 왕복하고 내부 순번은 싣지 않는다")
    void cursorRoundTrip() {
        // 준비: 생성 시각(한국 11/2 10:00:00.123456을 UTC로 바꾼 값)과 공개 ID로 커서를 만든다 (커서 = 목록 "다음 쪽"의 시작 위치 표시)
        Cursor cursor = new Cursor(Times.utc(OffsetDateTime.parse("2026-11-02T10:00:00.123456+09:00")),
                "IPv6mcQOlTkKj6mOecAQt");
        // 실행: 커서를 글자로 바꿨다가(encode) 다시 커서로 읽는다(decode)
        Cursor decoded = Cursor.decode(cursor.encode());
        // (도구) assertThat(실제값).isEqualTo(기대값) = "실제값이 기대값과 같아야 한다"는 검사
        // 확인: 다시 읽은 커서가 원래 커서와 똑같아야 한다 (왕복해도 값이 안 깨짐)
        assertThat(decoded).isEqualTo(cursor);
        // 확인: 커서가 없으면(null) 첫 쪽이라는 뜻으로 null이어야 한다
        assertThat(Cursor.decode(null)).isNull();
        // 확인: 공백뿐인 커서도 첫 쪽으로 보고 null이어야 한다
        assertThat(Cursor.decode(" ")).isNull();
    }

    // [상황] 깨진 커서를 넣는다.
    // [기대] 400 VALIDATION_FAILED.
    //        ※ 응답 코드: 400 = 요청 내용이 형식에 맞지 않음
    @Test
    @DisplayName("깨진 커서는 400 VALIDATION_FAILED")
    void brokenCursor() {
        // (도구) assertThatThrownBy(코드) = 그 코드를 실행하면 예외가 나야 한다는 검사
        // 실행+확인: 커서 형식이 아닌 글자를 읽으면 예외가 나야 한다
        assertThatThrownBy(() -> Cursor.decode("not-a-cursor"))
                // 확인: 예외 종류는 DomainException이고, 그 안의 에러 코드가
                .isInstanceOfSatisfying(DomainException.class,
                        // VALIDATION_FAILED(400)여야 한다
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.VALIDATION_FAILED));
    }

    // [상황] 로그인 토큰에서 사용자 정보를 만든다(렌탈 목록에 틀린 값 "bad" 섞임). 부스 토큰도 넣어 본다.
    // [기대] 고객 계정·역할·볼 수 있는 렌탈을 읽고 틀린 값은 버린다. 자기 렌탈만 볼 수 있다. 부스는 계정 번호가 없다.
    @Test
    @DisplayName("CurrentUser는 sub·role·rentals 클레임에서 만들고 NanoID가 아닌 값은 버린다")
    void currentUser() {
        // 준비: 로그인 토큰(JWT)을 직접 만든다. 서명 없음(alg none)으로 테스트용
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "none")
                // 준비: 클레임(토큰 안 정보)으로 고객 계정 ID, 역할 CUSTOMER를 넣고
                .claims(c -> c.putAll(Map.of("sub", "cust00000000000000001", "role", "CUSTOMER",
                        // 준비: 볼 수 있는 렌탈 목록에 올바른 ID 하나와 틀린 값 "bad"를 섞어 넣는다
                        "rentals", List.of("IPv6mcQOlTkKj6mOecAQt", "bad"))))
                // 준비: 토큰을 완성한다
                .build();
        // 실행: 토큰에서 로그인 사용자 정보를 만든다
        CurrentUser user = CurrentUser.from(jwt);
        // 확인: 계정 ID는 토큰 sub 값과 같아야 한다
        assertThat(user.accountId()).isEqualTo("cust00000000000000001");
        // 확인: 고객이니 관리자가 아니어야(false) 한다
        assertThat(user.isAdmin()).isFalse();
        // 확인: 볼 수 있는 렌탈 목록에는 올바른 ID 하나만 남아야 한다 ("bad"는 버림, containsExactly = 정확히 이것만)
        assertThat(user.rentals()).containsExactly("IPv6mcQOlTkKj6mOecAQt");
        // 확인: 자기 렌탈은 볼 수 있어야(true) 한다
        assertThat(user.canSee("IPv6mcQOlTkKj6mOecAQt")).isTrue();
        // 확인: 목록에 없는 남의 렌탈은 볼 수 없어야(false) 한다
        assertThat(user.canSee("other0000000000000001")).isFalse();

        // 준비: 부스 토큰(sub=booth-01, 역할 BOOTH)을 만든다
        Jwt booth = Jwt.withTokenValue("t").header("alg", "none").claim("sub", "booth-01").claim("role", "BOOTH").build();
        // 실행+확인: 부스는 사람 계정이 아니니 계정 ID가 비어(null) 있어야 한다 (booth-01은 21자 ID가 아님)
        assertThat(CurrentUser.from(booth).accountId()).isNull();
    }
}
