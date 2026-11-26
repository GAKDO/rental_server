// 테스트 도구(테스트 자체는 아님): 테스트용 로그인 토큰. 관리자·고객(볼 수 있는 렌탈 목록 포함)·부스를 흉내 낸다.

package com.gakdo.trace.rental.support;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

import java.util.List;

import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;

/** MockMvc용 JWT. auth-server가 넣는 클레임(sub=계정 publicId, role, rentals)을 흉내 낸다. */
public final class TestJwts {

    // 테스트용 관리자 계정 ID(21자). 토큰의 sub(누구인지) 칸에 들어간다
    public static final String ADMIN_ID = "admin0000000000000001";
    // 테스트용 고객 계정 ID(21자)
    public static final String CUSTOMER_ID = "cust00000000000000001";

    private TestJwts() {
    }

    // 관리자로 로그인한 요청을 흉내 내는 토큰을 만든다
    public static JwtRequestPostProcessor admin() {
        // (도구) jwt() = 진짜 로그인 없이 "이 토큰을 가진 사용자"로 요청을 보내게 해 주는 Spring Security 테스트 도구
        // sub=관리자 ID, role=ADMIN 클레임을 넣고, 서버가 권한 검사에 쓰는 ROLE_ADMIN 권한을 붙인다
        return jwt().jwt(j -> j.subject(ADMIN_ID).claim("role", "ADMIN"))
                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }

    // 고객으로 로그인한 요청을 흉내 낸다. rentals = 이 고객이 볼 수 있는 렌탈 ID 목록
    public static JwtRequestPostProcessor customer(String... rentals) {
        // sub=고객 ID, role=CUSTOMER, rentals=볼 수 있는 렌탈 목록 클레임을 넣고 ROLE_CUSTOMER 권한을 붙인다
        return jwt().jwt(j -> j.subject(CUSTOMER_ID).claim("role", "CUSTOMER").claim("rentals", List.of(rentals)))
                .authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"));
    }

    // 부스(촬영 장치)로 로그인한 요청을 흉내 낸다
    public static JwtRequestPostProcessor booth() {
        // sub=booth-01, role=BOOTH 클레임을 넣고 ROLE_BOOTH 권한을 붙인다
        return jwt().jwt(j -> j.subject("booth-01").claim("role", "BOOTH"))
                .authorities(new SimpleGrantedAuthority("ROLE_BOOTH"));
    }
}
