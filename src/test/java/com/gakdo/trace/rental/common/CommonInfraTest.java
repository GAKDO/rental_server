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

    @Test
    @DisplayName("깨진 커서는 400 VALIDATION_FAILED")
    void brokenCursor() {
        assertThatThrownBy(() -> Cursor.decode("not-a-cursor"))
                .isInstanceOfSatisfying(DomainException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.VALIDATION_FAILED));
    }

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
