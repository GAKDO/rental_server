package com.gakdo.trace.rental.rental.infra;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.domain.Specification;

import com.gakdo.trace.rental.common.infra.Cursor;
import com.gakdo.trace.rental.rental.domain.Checkpoint;
import com.gakdo.trace.rental.rental.domain.CheckpointType;
import com.gakdo.trace.rental.rental.domain.Rental;
import com.gakdo.trace.rental.rental.domain.RentalStatus;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Fetch;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;

/** 렌탈 목록 조건: 상태 + 기기(기기별 이력) + 문제 있는 검사만 + (CUSTOMER면) 볼 수 있는 렌탈 + 커서(최근 생성 순). */
public final class RentalSpecs {

    private static final String PUBLIC_ID = "publicId";
    private static final String CREATED_AT = "createdAt";

    private RentalSpecs() {
    }

    /**
     * @param deviceId null이면 모든 기기, 아니면 그 기기(publicId)의 렌탈만
     * @param problem  true면 지금 시점(가장 뒤에 시작한 시점)의 등급이 B·C·D인 렌탈만(11/10)
     * @param visible  null이면 전부(ADMIN), 아니면 그 publicId만
     * @param cursor   null이면 처음부터. 총 건수를 셀 때는 null로 준다
     */
    public static Specification<Rental> search(RentalStatus status, String deviceId, boolean problem,
            Collection<String> visible, Cursor cursor) {
        return (root, query, cb) -> {
            // 목록에 시리얼·기종 코드가 들어가 기기·기종을 함께 읽는다. 건수 세기(count)에는 fetch를 쓸 수 없어 건너뛴다
            boolean counting = query != null && Long.class.equals(query.getResultType());
            if (!counting) {
                Fetch<?, ?> device = root.fetch("device", JoinType.INNER);
                device.fetch("model", JoinType.INNER);
            }
            List<Predicate> where = new ArrayList<>();
            if (status != null) {
                where.add(cb.equal(root.get("status"), status));
            }
            if (deviceId != null) {
                where.add(cb.equal(root.get("device").get(PUBLIC_ID), deviceId));
            }
            if (problem && query != null) {
                where.add(currentCheckpointHasProblem(root, query.subquery(Long.class), query.subquery(Long.class), cb));
            }
            if (visible != null) {
                where.add(visible.isEmpty() ? cb.disjunction() : root.get(PUBLIC_ID).in(visible));
            }
            if (cursor != null) {
                where.add(after(root, cursor, cb));
            }
            return cb.and(where.toArray(Predicate[]::new));
        };
    }

    /** 커서보다 뒤(최근 생성 순으로 다음 쪽): 생성 시각이 이르거나, 같으면 publicId가 작은 것 */
    private static Predicate after(Root<Rental> root, Cursor cursor, CriteriaBuilder cb) {
        return cb.or(
                cb.lessThan(root.get(CREATED_AT), cursor.createdAt()),
                cb.and(cb.equal(root.get(CREATED_AT), cursor.createdAt()),
                        cb.lessThan(root.get(PUBLIC_ID), cursor.publicId())));
    }

    /**
     * 지금 시점(OUT → RCV → RET 중 가장 뒤에 시작한 것)의 등급이 B·C·D인지.
     * = 등급이 B·C·D인 시점 c가 있고, c보다 뒤 종류의 시점이 그 렌탈에 없다.
     */
    private static Predicate currentCheckpointHasProblem(Root<Rental> root, Subquery<Long> problemQuery,
            Subquery<Long> laterQuery, CriteriaBuilder cb) {
        Root<Checkpoint> c = problemQuery.from(Checkpoint.class);
        Root<Checkpoint> later = laterQuery.from(Checkpoint.class);
        laterQuery.select(later.get("id")).where(
                cb.equal(later.get("rental"), root),
                cb.or(
                        cb.and(cb.equal(c.get("type"), CheckpointType.OUT),
                                later.get("type").in(CheckpointType.RCV, CheckpointType.RET)),
                        cb.and(cb.equal(c.get("type"), CheckpointType.RCV),
                                cb.equal(later.get("type"), CheckpointType.RET))));
        problemQuery.select(c.get("id")).where(
                cb.equal(c.get("rental"), root),
                c.get("grade").in(Checkpoint.PROBLEM_GRADES),
                cb.not(cb.exists(laterQuery)));
        return cb.exists(problemQuery);
    }
}
