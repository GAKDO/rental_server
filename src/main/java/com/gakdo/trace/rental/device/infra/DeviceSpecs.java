package com.gakdo.trace.rental.device.infra;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.springframework.data.jpa.domain.Specification;

import com.gakdo.trace.rental.common.infra.Cursor;
import com.gakdo.trace.rental.device.domain.Device;
import com.gakdo.trace.rental.device.domain.DeviceStatus;
import com.gakdo.trace.rental.devicemodel.domain.DeviceModel;

import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;

/** 기기 목록 조건: 기종·상태·시리얼/자산번호 일부 + 커서(최근 등록 순 키셋). */
public final class DeviceSpecs {

    private DeviceSpecs() {
    }

    public static Specification<Device> search(String modelCode, DeviceStatus status, String q, Cursor cursor) {
        return (root, query, cb) -> {
            List<Predicate> where = new ArrayList<>();
            // 목록 응답에 기종 이름이 들어가 한 번에 읽는다(ManyToOne이라 limit와 함께 써도 안전)
            @SuppressWarnings("unchecked")
            Join<Device, DeviceModel> model = (Join<Device, DeviceModel>) (Join<?, ?>) root.fetch("model", JoinType.INNER);
            if (modelCode != null) {
                where.add(cb.equal(model.get("modelCode"), modelCode));
            }
            if (status != null) {
                where.add(cb.equal(root.get("status"), status));
            }
            if (q != null && !q.isBlank()) {
                String like = "%" + escape(q.toLowerCase(Locale.ROOT)) + "%";
                where.add(cb.or(
                        cb.like(cb.lower(root.get("serial")), like, '\\'),
                        cb.like(cb.lower(root.get("assetNo")), like, '\\')));
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

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
