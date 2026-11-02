package com.gakdo.trace.rental.rental.infra;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.domain.Specification;

import com.gakdo.trace.rental.common.infra.Cursor;
import com.gakdo.trace.rental.rental.domain.Rental;
import com.gakdo.trace.rental.rental.domain.RentalStatus;

import jakarta.persistence.criteria.Fetch;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;

/** 렌탈 목록 조건: 상태 + (CUSTOMER면) 볼 수 있는 렌탈 + 커서(최근 생성 순). */
public final class RentalSpecs {

    private RentalSpecs() {
    }

    /** @param visible null이면 전부(ADMIN), 아니면 그 publicId만 */
    public static Specification<Rental> search(RentalStatus status, Collection<String> visible, Cursor cursor) {
        return (root, query, cb) -> {
            // 목록에 시리얼·기종 코드가 들어가 기기·기종을 함께 읽는다
            Fetch<?, ?> device = root.fetch("device", JoinType.INNER);
            device.fetch("model", JoinType.INNER);
            List<Predicate> where = new ArrayList<>();
            if (status != null) {
                where.add(cb.equal(root.get("status"), status));
            }
            if (visible != null) {
                where.add(visible.isEmpty() ? cb.disjunction() : root.get("publicId").in(visible));
            }
            if (cursor != null) {
                where.add(cb.or(
                        cb.lessThan(root.get("createdAt"), cursor.createdAt()),
                        cb.and(cb.equal(root.get("createdAt"), cursor.createdAt()),
                                cb.lessThan(root.get("publicId"), cursor.publicId()))));
            }
            return cb.and(where.toArray(Predicate[]::new));
        };
    }
}
