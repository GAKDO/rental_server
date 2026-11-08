package com.gakdo.trace.rental.catalog.infra;

import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import com.gakdo.trace.rental.catalog.application.CatalogStore;

/** 공개 기기 목록 조회. 렌탈 수·진행 중 여부를 기기마다 따로 부르지 않게 한 쿼리로 모은다(N+1 방지). */
@Repository
public class JdbcCatalogStore implements CatalogStore {

    private static final String DEVICES = """
            select d.id, d.public_id, d.os, d.color, d.status,
                   m.model_code, m.name, m.manufacturer, m.spec::text as spec,
                   m.width_mm, m.depth_mm, m.base_h_mm, m.lid_h_mm,
                   (select count(*) from rental.rental r where r.device_id = d.id) as rental_count,
                   exists(select 1 from rental.rental r where r.device_id = d.id and r.status = 'OPEN') as has_open
              from rental.device d
              join rental.device_model m on m.id = d.device_model_id
             where d.status <> 'RETIRED'
             order by d.created_at desc, d.id desc
             limit :limit
            """;

    private static final String CHECKPOINTS = """
            select r.device_id, c.public_id, c.type, c.status, c.occurred_at
              from rental.checkpoint c
              join rental.rental r on r.id = c.rental_id
             where r.device_id in (:ids) and c.status in ('GRADED', 'SEALED')
             order by coalesce(c.occurred_at, c.updated_at) desc, c.id desc
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcCatalogStore(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<DeviceRow> devices(int limit) {
        return jdbc.query(DEVICES, Map.of("limit", limit), (rs, i) -> new DeviceRow(rs.getLong("id"),
                rs.getString("public_id"), rs.getString("os"), rs.getString("color"), rs.getString("status"),
                rs.getString("model_code"), rs.getString("name"), rs.getString("manufacturer"), rs.getString("spec"),
                rs.getBigDecimal("width_mm"), rs.getBigDecimal("depth_mm"), rs.getBigDecimal("base_h_mm"),
                rs.getBigDecimal("lid_h_mm"), rs.getLong("rental_count"), rs.getBoolean("has_open")));
    }

    @Override
    public List<CheckpointRow> finishedCheckpoints(List<Long> deviceIds) {
        if (deviceIds.isEmpty()) {
            return List.of();
        }
        return jdbc.query(CHECKPOINTS, Map.of("ids", deviceIds), (rs, i) -> new CheckpointRow(rs.getLong("device_id"),
                rs.getString("public_id"), rs.getString("type"), rs.getString("status"),
                rs.getObject("occurred_at", java.time.OffsetDateTime.class)));
    }
}
