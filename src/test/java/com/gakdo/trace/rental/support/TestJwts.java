package com.gakdo.trace.rental.support;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

import java.util.List;

import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;

/** MockMvc용 JWT. auth-server가 넣는 클레임(sub=계정 publicId, role, rentals)을 흉내 낸다. */
public final class TestJwts {

    public static final String ADMIN_ID = "admin0000000000000001";
    public static final String CUSTOMER_ID = "cust00000000000000001";

    private TestJwts() {
    }

    public static JwtRequestPostProcessor admin() {
        return jwt().jwt(j -> j.subject(ADMIN_ID).claim("role", "ADMIN"))
                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }

    public static JwtRequestPostProcessor customer(String... rentals) {
        return jwt().jwt(j -> j.subject(CUSTOMER_ID).claim("role", "CUSTOMER").claim("rentals", List.of(rentals)))
                .authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"));
    }

    public static JwtRequestPostProcessor booth() {
        return jwt().jwt(j -> j.subject("booth-01").claim("role", "BOOTH"))
                .authorities(new SimpleGrantedAuthority("ROLE_BOOTH"));
    }
}
