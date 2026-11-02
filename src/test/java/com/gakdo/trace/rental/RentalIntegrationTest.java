package com.gakdo.trace.rental;

import static com.gakdo.trace.rental.support.TestJwts.admin;
import static com.gakdo.trace.rental.support.TestJwts.customer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gakdo.trace.rental.common.infra.PublicIds;
import com.gakdo.trace.rental.rental.infra.AmqpConfig;

/**
 * 실제 PostgreSQL(Flyway V1 + Hibernate validate)과 RabbitMQ로 수용 기준을 끝까지 확인한다.
 * Docker가 없는 Claude 세션에서는 건너뛰고 GitHub Actions에서 돈다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class RentalIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("trace");

    @Container
    @ServiceConnection
    static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:3.13-management-alpine");

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private RabbitTemplate rabbit;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("같은 시리얼로 진행 중인 렌탈이 2건 생기지 않는다(동시 요청 10개 중 1개만 201)")
    void onlyOneOpenRentalUnderConcurrency() throws Exception {
        upsertModel("CONC-1");
        String deviceId = registerDevice("SER-CONC-1", "CONC-1");
        int threads = 10;
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        try (var pool = Executors.newFixedThreadPool(threads)) {
            for (int i = 0; i < threads; i++) {
                Callable<Integer> call = () -> {
                    start.await();
                    return mvc.perform(post("/api/v1/rentals").with(admin()).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"deviceId\":\"" + deviceId + "\",\"renterRef\":\"R\"}"))
                            .andReturn().getResponse().getStatus();
                };
                results.add(pool.submit(call));
            }
            start.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> f : results) {
                statuses.add(f.get());
            }
            assertThat(statuses).filteredOn(s -> s == 201).hasSize(1);
            assertThat(statuses).filteredOn(s -> s == 409).hasSize(threads - 1);
        }
        Integer open = jdbc.queryForObject(
                "select count(*) from rental.rental r join rental.device d on d.id = r.device_id "
                        + "where d.serial = 'SER-CONC-1' and r.status = 'OPEN'", Integer.class);
        assertThat(open).isEqualTo(1);
    }

    @Test
    @DisplayName("세 시점 흐름: 출고 전 수령 거부 → 이벤트로 봉인 → RETURNED → 종료, 그 뒤 같은 기기로 새 렌탈")
    void threeCheckpointFlow() throws Exception {
        upsertModel("FLOW-1");
        String deviceId = registerDevice("SER-FLOW-1", "FLOW-1");
        String rentalId = openRental(deviceId);

        startCheckpoint(rentalId, "RCV").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CHECKPOINT_ORDER_VIOLATION"));
        String out = checkpointId(startCheckpoint(rentalId, "OUT").andExpect(status().isCreated()).andReturn());
        startCheckpoint(rentalId, "RCV").andExpect(status().isConflict());

        publish(AmqpConfig.CAPTURE_UPLOADED, rentalId, out);
        awaitStatus(rentalId, 0, "CAPTURED");
        publish(AmqpConfig.CHECKPOINT_GRADED, rentalId, out);
        publish(AmqpConfig.RECORD_SEALED, rentalId, out);
        publish(AmqpConfig.RECORD_SEALED, rentalId, out); // 같은 이벤트가 두 번 와도 결과가 같다
        awaitStatus(rentalId, 0, "SEALED");

        mvc.perform(post("/api/v1/rentals/" + rentalId + "/close").with(admin()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("RENTAL_NOT_CLOSABLE"));

        String rcv = checkpointId(startCheckpoint(rentalId, "RCV").andExpect(status().isCreated()).andReturn());
        publish(AmqpConfig.RECORD_SEALED, rentalId, rcv);
        awaitStatus(rentalId, 1, "SEALED");
        String ret = checkpointId(startCheckpoint(rentalId, "RET").andExpect(status().isCreated()).andReturn());
        publish(AmqpConfig.RECORD_SEALED, rentalId, ret);
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                mvc.perform(get("/api/v1/rentals/" + rentalId).with(admin()))
                        .andExpect(jsonPath("$.status").value("RETURNED")));

        mvc.perform(get("/api/v1/rentals/" + rentalId).with(admin()))
                .andExpect(jsonPath("$.checkpoints.length()").value(3))
                .andExpect(jsonPath("$.checkpoints[0].occurredAt").isNotEmpty())
                .andExpect(jsonPath("$.startedAt").isNotEmpty());
        mvc.perform(post("/api/v1/rentals/" + rentalId + "/close").with(admin())).andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/rentals/" + rentalId).with(customer(rentalId)))
                .andExpect(jsonPath("$.status").value("CLOSED"))
                .andExpect(jsonPath("$.endedAt").isNotEmpty());
        mvc.perform(get("/api/v1/rentals/" + rentalId).with(customer())).andExpect(status().isForbidden());

        openRental(deviceId);
    }

    @Test
    @DisplayName("수리 중·폐기 기기로 렌탈을 만들면 409이고 폐기해도 행은 남는다, 진행 중 렌탈이 있으면 상태를 못 바꾼다")
    void retiredDeviceKeepsRow() throws Exception {
        upsertModel("RET-1");
        String busy = registerDevice("SER-RET-BUSY", "RET-1");
        openRental(busy);
        mvc.perform(patch("/api/v1/devices/" + busy).with(admin()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"RETIRED\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DEVICE_HAS_OPEN_RENTAL"));

        String deviceId = registerDevice("SER-RET-1", "RET-1");
        mvc.perform(patch("/api/v1/devices/" + deviceId).with(admin()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"RETIRED\",\"color\":null}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RETIRED"))
                .andExpect(jsonPath("$.retiredAt").isNotEmpty())
                .andExpect(jsonPath("$.color").isEmpty());
        mvc.perform(post("/api/v1/rentals").with(admin()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"deviceId\":\"" + deviceId + "\",\"renterRef\":\"R\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DEVICE_NOT_ACTIVE"));
        mvc.perform(get("/api/v1/devices?status=RETIRED&modelCode=RET-1").with(admin()))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].deviceId").value(deviceId));
        assertThat(jdbc.queryForObject("select updated_by from rental.device where public_id = ?", String.class,
                deviceId)).isEqualTo("admin0000000000000001");
    }

    @Test
    @DisplayName("시리얼 중복은 409, 없는 기종은 404, 모델명으로 3D 트윈 치수를 조회한다")
    void deviceAndModelRules() throws Exception {
        upsertModel("DIM-1");
        registerDevice("SER-DUP-1", "DIM-1");
        mvc.perform(post("/api/v1/devices").with(admin()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"serial\":\"SER-DUP-1\",\"modelCode\":\"DIM-1\",\"os\":\"MACOS\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DEVICE_SERIAL_EXISTS"));
        mvc.perform(post("/api/v1/devices").with(admin()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"serial\":\"SER-X\",\"modelCode\":\"NONE-1\",\"os\":\"MACOS\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/device-models/DIM-1").with(customer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dimensions.widthMm").value(304.1))
                .andExpect(jsonPath("$.spec.cpu").value("Apple M2"));
    }

    @Test
    @DisplayName("기기 목록은 기종·상태·시리얼 일부로 찾고 커서로 겹침 없이 넘긴다")
    void devicePaging() throws Exception {
        upsertModel("PAGE-1");
        for (int i = 0; i < 5; i++) {
            registerDevice("PG-SER-" + i, "PAGE-1");
        }
        List<String> seen = new ArrayList<>();
        String cursor = null;
        do {
            String url = "/api/v1/devices?modelCode=PAGE-1&q=pg-ser&limit=2" + (cursor == null ? "" : "&cursor=" + cursor);
            JsonNode page = json.readTree(mvc.perform(get(url).with(admin())).andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString());
            page.path("items").forEach(item -> seen.add(item.path("serial").asText()));
            cursor = page.path("nextCursor").isNull() ? null : page.path("nextCursor").asText();
        } while (cursor != null);
        assertThat(seen).containsExactly("PG-SER-4", "PG-SER-3", "PG-SER-2", "PG-SER-1", "PG-SER-0");
    }

    private void upsertModel(String code) throws Exception {
        mvc.perform(put("/api/v1/device-models/" + code).with(admin()).contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"name":"MacBook Air (M2, 2022)","manufacturer":"Apple","formFactor":"CLAMSHELL",
                         "spec":{"cpu":"Apple M2","ramGb":8,"ssdGb":256},
                         "dimensions":{"widthMm":304.1,"depthMm":215.0,"baseHMm":7.0,"lidHMm":4.3}}
                        """))
                .andExpect(status().isOk());
    }

    private String registerDevice(String serial, String model) throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/devices").with(admin()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"serial\":\"" + serial + "\",\"modelCode\":\"" + model + "\",\"os\":\"MACOS\",\"color\":\"실버\"}"))
                .andExpect(status().isCreated()).andReturn();
        String id = json.readTree(result.getResponse().getContentAsString()).path("deviceId").asText();
        assertThat(PublicIds.isValid(id)).isTrue();
        return id;
    }

    private String openRental(String deviceId) throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/rentals").with(admin()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"deviceId\":\"" + deviceId + "\",\"renterRef\":\"R-1\"}"))
                .andExpect(status().isCreated()).andReturn();
        return json.readTree(result.getResponse().getContentAsString()).path("rentalId").asText();
    }

    private org.springframework.test.web.servlet.ResultActions startCheckpoint(String rentalId, String type)
            throws Exception {
        return mvc.perform(post("/api/v1/rentals/" + rentalId + "/checkpoints").with(admin())
                .contentType(MediaType.APPLICATION_JSON).content("{\"type\":\"" + type + "\"}"));
    }

    private String checkpointId(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString()).path("checkpointId").asText();
    }

    private void publish(String routingKey, String rentalId, String checkpointId) {
        String body = """
                {"schemaVersion":"1.0","eventId":"%s","occurredAt":"2026-11-09T14:05:12+09:00",
                 "rentalId":"%s","checkpointId":"%s"}
                """.formatted(PublicIds.next(), rentalId, checkpointId);
        MessageProperties props = new MessageProperties();
        props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        rabbit.send(AmqpConfig.EXCHANGE, routingKey, MessageBuilder.withBody(body.getBytes()).andProperties(props).build());
    }

    private void awaitStatus(String rentalId, int index, String expected) {
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                mvc.perform(get("/api/v1/rentals/" + rentalId).with(admin()))
                        .andExpect(jsonPath("$.checkpoints[" + index + "].status").value(expected)));
    }
}
