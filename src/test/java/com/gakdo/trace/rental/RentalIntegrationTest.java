package com.gakdo.trace.rental;

import static com.gakdo.trace.rental.support.TestJwts.admin;
import static com.gakdo.trace.rental.support.TestJwts.booth;
import static com.gakdo.trace.rental.support.TestJwts.customer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
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

    @Autowired
    private AmqpAdmin amqpAdmin;

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
        Queue started = new Queue("test.checkpoint.started", false, false, false);
        amqpAdmin.declareQueue(started);
        amqpAdmin.purgeQueue(started.getName(), false);
        amqpAdmin.declareBinding(BindingBuilder.bind(started).to(new TopicExchange(AmqpConfig.EXCHANGE))
                .with("trace.checkpoint.started"));

        startCheckpoint(rentalId, "RCV").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CHECKPOINT_ORDER_VIOLATION"));
        String out = checkpointId(startCheckpoint(rentalId, "OUT").andExpect(status().isCreated()).andReturn());
        // 1.1: 시점을 시작하면 기종·시리얼을 실은 trace.checkpoint.started가 한 번 나간다(거절된 RCV는 안 나감)
        assertStartedOnce(started, rentalId, out, deviceId);
        assertOutCheckpointListed(rentalId);
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
        // 반납된 렌탈은 부스가 status를 바꿔 보내도 목록에 안 나온다(OPEN 고정)
        mvc.perform(get("/api/v1/rentals?status=RETURNED").with(booth()))
                .andExpect(jsonPath("$.items[?(@.rentalId == '" + rentalId + "')]").isEmpty());

        assertClosesAfterReturn(rentalId);
        // 공개 보유 기기 목록(11/8): 로그인 없이, 봉인된 세 시점이 최근 것부터, 시리얼 없이
        mvc.perform(get("/api/v1/catalog/devices"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.deviceId == '" + deviceId + "')].deviceId").value(hasSize(1)))
                .andExpect(jsonPath("$.items[?(@.deviceId == '" + deviceId + "')].checkpoints[0].checkpointId").value(ret))
                .andExpect(jsonPath("$.items[?(@.deviceId == '" + deviceId + "')].available").value(true))
                .andExpect(jsonPath("$.items[?(@.deviceId == '" + deviceId + "')].serial").doesNotExist());

        openRental(deviceId);
    }

    /** 시점 시작 이벤트(trace.checkpoint.started 1.1)가 기종·시리얼을 싣고 한 번만 나갔는지 본다. */
    private void assertStartedOnce(Queue started, String rentalId, String checkpointId, String deviceId)
            throws Exception {
        Message startedMessage = rabbit.receive(started.getName(), 10_000);
        assertThat(startedMessage).isNotNull();
        JsonNode startedEvent = json.readTree(startedMessage.getBody());
        assertThat(startedMessage.getMessageProperties().getMessageId()).isEqualTo(startedEvent.get("eventId").asText());
        assertThat(startedEvent.get("schemaVersion").asText()).isEqualTo("1.1");
        assertThat(startedEvent.get("rentalId").asText()).isEqualTo(rentalId);
        assertThat(startedEvent.get("checkpointId").asText()).isEqualTo(checkpointId);
        assertThat(startedEvent.get("checkpointType").asText()).isEqualTo("OUT");
        assertThat(startedEvent.get("deviceId").asText()).isEqualTo(deviceId);
        assertThat(startedEvent.get("serial").asText()).isEqualTo("SER-FLOW-1");
        assertThat(startedEvent.get("modelCode").asText()).isEqualTo("FLOW-1");
        assertThat(startedEvent.get("modelName").asText()).isNotBlank();
        assertThat(rabbit.receive(started.getName(), 500)).isNull();
    }

    /** 1.0.x 추가 필드: 상세에 시리얼, 목록에 지금 시점(트랜잭션 안에서 시점을 읽는지 실제 DB로 확인). */
    private void assertOutCheckpointListed(String rentalId) throws Exception {
        mvc.perform(get("/api/v1/rentals/" + rentalId).with(admin()))
                .andExpect(jsonPath("$.serial").value("SER-FLOW-1"));
        mvc.perform(get("/api/v1/rentals").with(admin()))
                .andExpect(jsonPath("$.items[?(@.rentalId == '" + rentalId + "')].currentCheckpoint.type").value("OUT"));
        // 부스 태블릿 기기 목록: 부스 토큰은 진행 중 렌탈을 rentals 클레임 없이 본다
        mvc.perform(get("/api/v1/rentals").with(booth()))
                .andExpect(jsonPath("$.items[?(@.rentalId == '" + rentalId + "')].currentCheckpoint.type").value("OUT"));
    }

    /** 세 시점이 끝나 RETURNED인 렌탈을 닫으면 CLOSED가 되고, 다른 고객은 볼 수 없다. */
    private void assertClosesAfterReturn(String rentalId) throws Exception {
        mvc.perform(get("/api/v1/rentals/" + rentalId).with(admin()))
                .andExpect(jsonPath("$.checkpoints.length()").value(3))
                .andExpect(jsonPath("$.checkpoints[0].occurredAt").isNotEmpty())
                .andExpect(jsonPath("$.startedAt").isNotEmpty());
        mvc.perform(post("/api/v1/rentals/" + rentalId + "/close").with(admin())).andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/rentals/" + rentalId).with(customer(rentalId)))
                .andExpect(jsonPath("$.status").value("CLOSED"))
                .andExpect(jsonPath("$.endedAt").isNotEmpty());
        mvc.perform(get("/api/v1/rentals/" + rentalId).with(customer())).andExpect(status().isForbidden());
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
    @DisplayName("폐기 기기는 사용 가능으로 바로 못 바꾸고(409), 수리 중으로는 되돌리며 retiredAt이 비워진다")
    void retiredDeviceTransitions() throws Exception {
        upsertModel("RET-2");
        String deviceId = registerDevice("SER-RET-2", "RET-2");
        mvc.perform(patch("/api/v1/devices/" + deviceId).with(admin()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"RETIRED\"}"))
                .andExpect(status().isOk());

        mvc.perform(patch("/api/v1/devices/" + deviceId).with(admin()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"ACTIVE\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DEVICE_STATUS_TRANSITION_INVALID"));
        assertThat(jdbc.queryForObject("select status from rental.device where public_id = ?", String.class,
                deviceId)).isEqualTo("RETIRED");

        mvc.perform(patch("/api/v1/devices/" + deviceId).with(admin()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"MAINTENANCE\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("MAINTENANCE"))
                .andExpect(jsonPath("$.retiredAt").isEmpty());
        mvc.perform(patch("/api/v1/devices/" + deviceId).with(admin()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"ACTIVE\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    @DisplayName("시험 렌탈(TEST-)을 지우면 렌탈·시점·기기가 사라지고 시점마다 purged가 나가며, 같은 시리얼로 다시 등록된다. 실제 렌탈은 409")
    void deleteTestRental() throws Exception {
        upsertModel("PURGE-1");
        String deviceId = registerDevice("TEST-PURGE-1", "PURGE-1");
        String rentalId = openRental(deviceId);
        String out = checkpointId(startCheckpoint(rentalId, "OUT").andExpect(status().isCreated()).andReturn());
        Queue purged = new Queue("test.checkpoint.purged", false, false, false);
        amqpAdmin.declareQueue(purged);
        amqpAdmin.purgeQueue(purged.getName(), false);
        amqpAdmin.declareBinding(BindingBuilder.bind(purged).to(new TopicExchange(AmqpConfig.EXCHANGE))
                .with("trace.checkpoint.purged"));

        mvc.perform(delete("/api/v1/rentals/" + rentalId).with(admin())).andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/rentals/" + rentalId).with(admin())).andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("select count(*) from rental.device where public_id = ?", Integer.class, deviceId))
                .isZero();
        Message message = rabbit.receive(purged.getName(), 10_000);
        assertThat(message).isNotNull();
        JsonNode event = json.readTree(message.getBody());
        assertThat(event.get("checkpointId").asText()).isEqualTo(out);
        assertThat(event.get("serial").asText()).isEqualTo("TEST-PURGE-1");
        assertThat(rabbit.receive(purged.getName(), 500)).isNull();
        registerDevice("TEST-PURGE-1", "PURGE-1");

        String real = openRental(registerDevice("SER-PURGE-2", "PURGE-1"));
        mvc.perform(delete("/api/v1/rentals/" + real).with(admin()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("NOT_TEST_DATA"));
        mvc.perform(get("/api/v1/rentals/" + real).with(admin())).andExpect(status().isOk());
    }

    @Test
    @DisplayName("기종 시드 V2: MBA13-M2가 치수 4개와 함께 조회되고, 시드를 다시 돌려도 한 건이다")
    void seedDeviceModel() throws Exception {
        mvc.perform(get("/api/v1/device-models/MBA13-M2").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("MacBook Air (M2, 2022)"))
                .andExpect(jsonPath("$.manufacturer").value("Apple"))
                .andExpect(jsonPath("$.formFactor").value("CLAMSHELL"))
                .andExpect(jsonPath("$.spec.cpu").value("Apple M2"))
                .andExpect(jsonPath("$.dimensions.widthMm").value(304.1))
                .andExpect(jsonPath("$.dimensions.depthMm").value(215.0))
                .andExpect(jsonPath("$.dimensions.baseHMm").value(7.0))
                .andExpect(jsonPath("$.dimensions.lidHMm").value(4.0));

        String seed = new String(getClass().getResourceAsStream("/db/migration/V2__seed_device_models.sql")
                .readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        jdbc.execute(seed);
        assertThat(jdbc.queryForObject("select count(*) from rental.device_model where model_code = 'MBA13-M2'",
                Integer.class)).isEqualTo(1);
    }

    @Test
    @DisplayName("부스 코드: 출고 전 발급은 409, 발급한 코드로 세 ID를 한 번만 받고, 다시 발급하면 앞 코드는 못 쓰며, 찍은 시점의 코드는 410")
    void boothCodeFlow() throws Exception {
        upsertModel("CODE-1");
        String deviceId = registerDevice("TEST-CODE-1", "CODE-1");
        String rentalId = openRental(deviceId);
        mvc.perform(post("/api/v1/rentals/" + rentalId + "/booth-code").with(admin()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CHECKPOINT_ORDER_VIOLATION"));
        String out = checkpointId(startCheckpoint(rentalId, "OUT").andExpect(status().isCreated()).andReturn());

        String first = issueBoothCode(rentalId, out);
        String second = issueBoothCode(rentalId, out);
        redeem(first).andExpect(status().isGone()).andExpect(jsonPath("$.code").value("BOOTH_CODE_EXPIRED"));
        redeem(second).andExpect(status().isOk())
                .andExpect(jsonPath("$.rentalId").value(rentalId))
                .andExpect(jsonPath("$.deviceId").value(deviceId))
                .andExpect(jsonPath("$.serial").value("TEST-CODE-1"))
                .andExpect(jsonPath("$.modelCode").value("CODE-1"))
                .andExpect(jsonPath("$.checkpointId").value(out))
                .andExpect(jsonPath("$.checkpointType").value("OUT"));
        redeem(second).andExpect(status().isGone());
        assertThat(jdbc.queryForObject("select count(*) from rental.booth_code where code_hash = ?", Integer.class,
                sha256(second))).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from rental.booth_code where code_hash = ?", Integer.class,
                second)).isZero(); // 원문은 저장하지 않는다

        String third = issueBoothCode(rentalId, out);
        publish(AmqpConfig.CAPTURE_UPLOADED, rentalId, out);
        awaitStatus(rentalId, 0, "CAPTURED");
        redeem(third).andExpect(status().isGone());
        mvc.perform(post("/api/v1/rentals/" + rentalId + "/booth-code").with(admin()))
                .andExpect(status().isConflict());

        // 시험 렌탈을 지우면 코드도 함께 지워진다(외래 키 ON DELETE CASCADE)
        mvc.perform(delete("/api/v1/rentals/" + rentalId).with(admin())).andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("select count(*) from rental.booth_code", Integer.class)).isZero();
    }

    private String issueBoothCode(String rentalId, String checkpointId) throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/rentals/" + rentalId + "/booth-code").with(admin()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.checkpointId").value(checkpointId))
                .andExpect(jsonPath("$.checkpointType").value("OUT"))
                .andReturn();
        String code = json.readTree(result.getResponse().getContentAsString()).path("code").asText();
        assertThat(code).matches("[0-9]{6}");
        return code;
    }

    private org.springframework.test.web.servlet.ResultActions redeem(String code) throws Exception {
        return mvc.perform(post("/api/v1/rentals/booth-codes/redeem").with(booth()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"" + code + "\"}"));
    }

    private static String sha256(String value) throws Exception {
        return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
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

    @Test
    @DisplayName("기기별 렌탈 이력: deviceId로 그 기기 렌탈만 최근 순으로, status와 겹쳐 거르고, 기기 상세는 ADMIN에게 보인다")
    void deviceRentalHistory() throws Exception {
        upsertModel("HIST-1");
        String mine = registerDevice("SER-HIST-A", "HIST-1");
        String other = registerDevice("SER-HIST-B", "HIST-1");
        String rental = openRental(mine);
        openRental(other);
        mvc.perform(get("/api/v1/rentals?deviceId=" + mine).with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].rentalId").value(rental))
                .andExpect(jsonPath("$.items[0].renterRef").value("R-1"))
                .andExpect(jsonPath("$.items[0].startedAt").isEmpty())
                .andExpect(jsonPath("$.items[0].endedAt").isEmpty());
        mvc.perform(get("/api/v1/rentals?deviceId=" + mine + "&status=CLOSED").with(admin()))
                .andExpect(jsonPath("$.items.length()").value(0));
        mvc.perform(get("/api/v1/rentals?deviceId=" + PublicIds.next()).with(admin()))
                .andExpect(jsonPath("$.items.length()").value(0));
        mvc.perform(get("/api/v1/devices/" + mine).with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.serial").value("SER-HIST-A"));
        mvc.perform(get("/api/v1/devices/" + PublicIds.next()).with(admin()))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("문제 있는 검사만 보기: 지금 시점 등급이 B·C·D인 렌탈만 나오고 totalCount가 같은 조건의 건수다. 다음 시점을 시작하면 빠진다")
    void problemFilterAndTotalCount() throws Exception {
        upsertModel("PROB-1");
        String deviceId = registerDevice("SER-PROB-1", "PROB-1");
        String rentalId = openRental(deviceId);
        String out = checkpointId(startCheckpoint(rentalId, "OUT").andExpect(status().isCreated()).andReturn());
        String onlyThis = "/api/v1/rentals?deviceId=" + deviceId + "&problem=true";
        mvc.perform(get(onlyThis).with(admin()))
                .andExpect(jsonPath("$.items.length()").value(0)).andExpect(jsonPath("$.totalCount").value(0));

        publishGraded(rentalId, out, "C");
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> mvc.perform(get(onlyThis).with(admin()))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].rentalId").value(rentalId))
                .andExpect(jsonPath("$.totalCount").value(1)));
        mvc.perform(get("/api/v1/rentals?deviceId=" + deviceId + "&limit=1").with(admin()))
                .andExpect(jsonPath("$.totalCount").value(1));

        publish(AmqpConfig.RECORD_SEALED, rentalId, out);
        awaitStatus(rentalId, 0, "SEALED");
        String rcv = checkpointId(startCheckpoint(rentalId, "RCV").andExpect(status().isCreated()).andReturn());
        mvc.perform(get(onlyThis).with(admin())).andExpect(jsonPath("$.items.length()").value(0));
        publishGraded(rentalId, rcv, "A");
        publishGraded(rentalId, rcv, "D"); // 다시 검수하면 마지막 등급으로
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> mvc.perform(get(onlyThis).with(admin()))
                .andExpect(jsonPath("$.items.length()").value(1)));
    }

    @Test
    @DisplayName("렌탈 고객 이름·반납 예정일·메모: 생성 때 받고 PATCH로 고치며, 고객에게는 메모가 null이고 고객은 고칠 수 없다")
    void rentalDetails() throws Exception {
        upsertModel("DET-1");
        String deviceId = registerDevice("SER-DET-1", "DET-1");
        MvcResult created = mvc.perform(post("/api/v1/rentals").with(admin()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"deviceId\":\"" + deviceId + "\",\"renterRef\":\"R-1\",\"customerName\":\" 김고객 \","
                        + "\"dueAt\":\"2026-11-19\",\"note\":\"충전기 함께\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.customerName").value("김고객"))
                .andExpect(jsonPath("$.dueAt").value("2026-11-19"))
                .andExpect(jsonPath("$.note").value("충전기 함께"))
                .andReturn();
        String rentalId = json.readTree(created.getResponse().getContentAsString()).path("rentalId").asText();

        mvc.perform(patch("/api/v1/rentals/" + rentalId).with(admin()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"dueAt\":\"2026-11-20\",\"note\":null}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.customerName").value("김고객"))
                .andExpect(jsonPath("$.dueAt").value("2026-11-20"))
                .andExpect(jsonPath("$.note").isEmpty());
        mvc.perform(patch("/api/v1/rentals/" + rentalId).with(admin()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"note\":\"메모\"}")).andExpect(status().isOk());
        mvc.perform(get("/api/v1/rentals/" + rentalId).with(customer(rentalId)))
                .andExpect(jsonPath("$.customerName").value("김고객"))
                .andExpect(jsonPath("$.note").isEmpty());
        mvc.perform(get("/api/v1/rentals?deviceId=" + deviceId).with(admin()))
                .andExpect(jsonPath("$.items[0].customerName").value("김고객"));
        mvc.perform(patch("/api/v1/rentals/" + rentalId).with(customer(rentalId)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"note\":\"x\"}")).andExpect(status().isForbidden());
    }

    private void publishGraded(String rentalId, String checkpointId, String grade) {
        String body = """
                {"schemaVersion":"1.3","eventId":"%s","occurredAt":"2026-11-09T14:05:12+09:00",
                 "rentalId":"%s","checkpointId":"%s","grade":"%s"}
                """.formatted(PublicIds.next(), rentalId, checkpointId, grade);
        MessageProperties props = new MessageProperties();
        props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        rabbit.send(AmqpConfig.EXCHANGE, AmqpConfig.CHECKPOINT_GRADED,
                MessageBuilder.withBody(body.getBytes(java.nio.charset.StandardCharsets.UTF_8)).andProperties(props).build());
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
