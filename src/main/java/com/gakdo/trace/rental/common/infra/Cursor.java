package com.gakdo.trace.rental.common.infra;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;

import com.gakdo.trace.rental.common.domain.DomainException;

/**
 * 목록 커서: 마지막 항목의 (createdAt, publicId). 내부 순번 id는 커서에도 싣지 않는다. 최근 생성 순(내림차순) 키셋 페이지에 쓴다.
 * 형식은 서버 내부 약속이고 클라이언트는 불투명 문자열로 그대로 돌려준다.
 */
public record Cursor(OffsetDateTime createdAt, String publicId) {

    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    public String encode() {
        String raw = createdAt.toInstant().toString() + "|" + publicId;
        return ENCODER.encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    /** null·빈 값이면 처음부터(null). 깨진 값이면 400. */
    public static Cursor decode(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            String raw = new String(DECODER.decode(value), StandardCharsets.UTF_8);
            int bar = raw.indexOf('|');
            OffsetDateTime at = Instant.parse(raw.substring(0, bar)).atOffset(ZoneOffset.UTC);
            String publicId = raw.substring(bar + 1);
            if (!PublicIds.isValid(publicId)) {
                throw new IllegalArgumentException();
            }
            return new Cursor(Times.utc(at), publicId);
        } catch (RuntimeException e) {
            throw DomainException.invalid("cursor", "커서가 올바르지 않습니다");
        }
    }
}
