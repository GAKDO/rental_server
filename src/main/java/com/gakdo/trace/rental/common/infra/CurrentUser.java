package com.gakdo.trace.rental.common.infra;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.security.oauth2.jwt.Jwt;

/**
 * 요청한 사람. JWT의 sub(auth.account.id)·role·rentals 클레임에서 만든다.
 *
 * @param accountId updated_by에 남길 계정 publicId(sub). NanoID가 아니면(부스 client_id 등) null
 * @param rentals   CUSTOMER가 볼 수 있는 렌탈 ID
 */
public record CurrentUser(String accountId, String role, Set<String> rentals) {

    public static CurrentUser from(Jwt jwt) {
        return new CurrentUser(parse(jwt.getSubject()), jwt.getClaimAsString("role"), rentalsOf(jwt));
    }

    public boolean isAdmin() {
        return "ADMIN".equals(role);
    }

    public boolean canSee(String rentalId) {
        return isAdmin() || rentals.contains(rentalId);
    }

    private static Set<String> rentalsOf(Jwt jwt) {
        Object claim = jwt.getClaims().get("rentals");
        Collection<?> values = claim instanceof Collection<?> c ? c : List.of();
        return values.stream()
                .map(String::valueOf)
                .map(CurrentUser::parse)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toUnmodifiableSet());
    }

    private static String parse(String value) {
        return PublicIds.isValid(value) ? value : null;
    }
}
