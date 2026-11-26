// 이 파일이 확인하는 것: rental-server(렌탈·기기·기종·부스 코드 서버)의 주요 흐름을 진짜 DB(PostgreSQL)와
// 메시지 큐(RabbitMQ)로 끝까지 확인. 렌탈 한 건은 출고(OUT) → 수령(RCV) → 반납(RET) 세 시점을 순서대로 거친다.
// Docker가 없는 곳(Claude 세션)에서는 건너뛰고 GitHub Actions에서만 돈다.

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
// (도구) @SpringBootTest = 서버 전체(컨트롤러·서비스·DB 연결·메시지 큐)를 진짜로 띄우는 통합 테스트
// (도구) @AutoConfigureMockMvc = 그 서버에 요청을 보낼 MockMvc를 준비. @Testcontainers = 도커로 진짜 DB·RabbitMQ를 띄움(도커 없으면 건너뜀)
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class RentalIntegrationTest {

    // (도구) @Container = 테스트 동안 도커 컨테이너를 띄웠다가 끝나면 내린다. @ServiceConnection = 서버가 이 컨테이너 주소로 자동 연결
    // 준비: PostgreSQL 16 DB를 trace라는 이름으로 띄운다
    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("trace");

    // 준비: RabbitMQ(메시지 큐)를 띄운다
    @Container
    @ServiceConnection
    static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:3.13-management-alpine");

    // (도구) @Autowired = 스프링이 만든 객체를 넣어 준다. MockMvc = 서버에 HTTP 요청을 흉내 내 보내는 도구
    @Autowired
    private MockMvc mvc;

    // JSON 글자를 읽는 변환기
    @Autowired
    private ObjectMapper json;

    // 메시지 큐에 메시지를 넣고 꺼내는 도구 (이벤트를 보내거나 받은 이벤트를 확인할 때)
    @Autowired
    private RabbitTemplate rabbit;

    // DB에 SQL을 직접 실행하는 도구 (API로 안 보이는 DB 상태를 확인할 때)
    @Autowired
    private JdbcTemplate jdbc;

    // 메시지 큐에 시험용 큐를 만들고 연결하는 관리 도구
    @Autowired
    private AmqpAdmin amqpAdmin;

    // [상황] 같은 노트북(같은 일련번호)으로 렌탈 만들기 요청 10개가 동시에 들어온다.
    // [기대] 1개만 성공(201)하고 나머지는 거절된다. 한 기기에 진행 중 렌탈이 둘 생기지 않는다.
    //        ※ 응답 코드: 201 = 새로 만들어짐
    // (도구) @Test = JUnit에게 "이 메서드가 테스트 하나"라고 알리는 표시. @DisplayName = 결과 화면에 보일 한글 이름
    @Test
    @DisplayName("같은 시리얼로 진행 중인 렌탈이 2건 생기지 않는다(동시 요청 10개 중 1개만 201)")
    void onlyOneOpenRentalUnderConcurrency() throws Exception {
        // 준비: 시험용 기종 CONC-1을 저장한다 (아래 upsertModel 도우미)
        upsertModel("CONC-1");
        // 준비: 그 기종으로 기기(시리얼 SER-CONC-1)를 등록하고 기기 ID를 받는다
        String deviceId = registerDevice("SER-CONC-1", "CONC-1");
        // 준비: 동시에 보낼 요청 수 10
        int threads = 10;
        // 준비: 출발 신호기(CountDownLatch). 10개가 모두 준비될 때까지 기다렸다가 한꺼번에 출발시키려고
        CountDownLatch start = new CountDownLatch(1);
        // 준비: 각 요청 결과(응답 코드)를 나중에 받을 확인표 목록
        List<Future<Integer>> results = new ArrayList<>();
        // 준비: 스레드 10개짜리 작업 풀을 만든다 (try(...) = 끝나면 자동으로 닫음)
        try (var pool = Executors.newFixedThreadPool(threads)) {
            // 준비: 10번 되풀이하며 작업을 만든다
            for (int i = 0; i < threads; i++) {
                // 작업 하나: 응답 코드(정수)를 돌려주는 일
                Callable<Integer> call = () -> {
                    // 출발 신호가 올 때까지 기다린다
                    start.await();
                    // 관리자로 같은 기기에 렌탈 생성 요청을 보내고
                    return mvc.perform(post("/api/v1/rentals").with(admin()).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"deviceId\":\"" + deviceId + "\",\"renterRef\":\"R\"}"))
                            // 응답 코드(201·409 등)를 돌려준다
                            .andReturn().getResponse().getStatus();
                };
                // 작업을 풀에 맡기고 확인표를 목록에 넣는다
                results.add(pool.submit(call));
            }
            // 실행: 출발 신호를 보내 10개 요청이 동시에 출발하게 한다
            start.countDown();
            // 실행: 응답 코드를 모을 목록을 만든다
            List<Integer> statuses = new ArrayList<>();
            // 실행: 확인표 하나하나에 대해
            for (Future<Integer> f : results) {
                // 그 요청이 끝날 때까지 기다려 응답 코드를 모은다
                statuses.add(f.get());
            }
            // (도구) assertThat(목록).filteredOn(조건).hasSize(n) = 목록에서 조건에 맞는 것만 골라 개수가 n이어야 한다는 검사
            // 확인: 201(성공)은 정확히 1개여야 한다
            assertThat(statuses).filteredOn(s -> s == 201).hasSize(1);
            // 확인: 나머지 9개는 409(이미 있음)여야 한다
            assertThat(statuses).filteredOn(s -> s == 409).hasSize(threads - 1);
        }
        // 확인: DB에서 이 시리얼 기기의 진행 중(OPEN) 렌탈 수를 직접 센다
        Integer open = jdbc.queryForObject(
                "select count(*) from rental.rental r join rental.device d on d.id = r.device_id "
                        + "where d.serial = 'SER-CONC-1' and r.status = 'OPEN'", Integer.class);
        // (도구) assertThat(실제값).isEqualTo(기대값) = "실제값이 기대값과 같아야 한다"는 검사
        // 확인: DB에도 진행 중 렌탈은 1건뿐이어야 한다 (동시 요청에도 중복 생성 없음)
        assertThat(open).isEqualTo(1);
    }

    // [상황] 출고 전에 수령을 시작해 보고 → 출고·수령·반납을 촬영·봉인 이벤트로 차례로 진행 → 렌탈 종료 → 같은 기기로 새 렌탈.
    // [기대] 출고 전 수령은 거부, 세 시점이 봉인되면 반납 완료(RETURNED), 종료하면 CLOSED, 그 뒤 새 렌탈을 만들 수 있다.
    @Test
    @DisplayName("세 시점 흐름: 출고 전 수령 거부 → 이벤트로 봉인 → RETURNED → 종료, 그 뒤 같은 기기로 새 렌탈")
    void threeCheckpointFlow() throws Exception {
        // 준비: 시험용 기종 FLOW-1을 저장한다
        upsertModel("FLOW-1");
        // 준비: 기기를 등록한다
        String deviceId = registerDevice("SER-FLOW-1", "FLOW-1");
        // 준비: 그 기기로 렌탈을 연다
        String rentalId = openRental(deviceId);
        // 준비: "시점 시작" 이벤트를 받아 볼 시험용 큐를 만든다 (서버가 내보내는 이벤트를 엿보려고)
        Queue started = new Queue("test.checkpoint.started", false, false, false);
        // 준비: 메시지 큐 서버에 그 큐를 만든다
        amqpAdmin.declareQueue(started);
        // 준비: 큐에 남은 옛 메시지를 비운다 (다른 테스트 영향 제거)
        amqpAdmin.purgeQueue(started.getName(), false);
        // 준비: 이벤트 교환소(trace.events)에서 trace.checkpoint.started 이벤트가 이 큐로 오게 연결한다
        amqpAdmin.declareBinding(BindingBuilder.bind(started).to(new TopicExchange(AmqpConfig.EXCHANGE))
                .with("trace.checkpoint.started"));

        // 실행: 출고 전에 수령(RCV)을 시작해 보고, 409여야 한다
        startCheckpoint(rentalId, "RCV").andExpect(status().isConflict())
                // 확인: 에러 코드가 CHECKPOINT_ORDER_VIOLATION이어야 한다
                .andExpect(jsonPath("$.code").value("CHECKPOINT_ORDER_VIOLATION"));
        // 실행: 출고(OUT)를 시작해 201을 확인하고 출고 시점 ID를 받는다
        String out = checkpointId(startCheckpoint(rentalId, "OUT").andExpect(status().isCreated()).andReturn());
        // 1.1: 시점을 시작하면 기종·시리얼을 실은 trace.checkpoint.started가 한 번 나간다(거절된 RCV는 안 나감)
        // 확인: 시점 시작 이벤트가 한 번만, 내용이 맞게 나갔는지 본다 (아래 도우미)
        assertStartedOnce(started, rentalId, out, deviceId);
        // 확인: 상세·목록에 출고 시점이 보이는지 본다 (아래 도우미)
        assertOutCheckpointListed(rentalId);
        // 실행+확인: 출고가 아직 봉인 전이라 수령 시작은 여전히 409여야 한다
        startCheckpoint(rentalId, "RCV").andExpect(status().isConflict());

        // 실행: 다른 서버가 보낸 것처럼 "사진 업로드됨" 이벤트를 큐에 넣는다
        publish(AmqpConfig.CAPTURE_UPLOADED, rentalId, out);
        // 확인: 첫 시점(출고)이 촬영됨(CAPTURED)이 될 때까지 최대 10초 기다린다 (이벤트는 따로 처리돼 시간이 걸림)
        awaitStatus(rentalId, 0, "CAPTURED");
        // 실행: "등급 매김" 이벤트를 넣는다
        publish(AmqpConfig.CHECKPOINT_GRADED, rentalId, out);
        // 실행: "봉인됨" 이벤트를 넣는다
        publish(AmqpConfig.RECORD_SEALED, rentalId, out);
        // 실행: 같은 봉인 이벤트를 한 번 더 넣는다 (중복 배달 상황)
        publish(AmqpConfig.RECORD_SEALED, rentalId, out); // 같은 이벤트가 두 번 와도 결과가 같다
        // 확인: 출고가 봉인(SEALED)될 때까지 기다린다
        awaitStatus(rentalId, 0, "SEALED");

        // 실행: 반납 전에 렌탈을 종료해 본다
        mvc.perform(post("/api/v1/rentals/" + rentalId + "/close").with(admin()))
                // 확인: 409이고 에러 코드가 RENTAL_NOT_CLOSABLE이어야 한다
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("RENTAL_NOT_CLOSABLE"));

        // 실행: 출고가 봉인됐으니 수령을 시작해 201을 확인하고 수령 시점 ID를 받는다
        String rcv = checkpointId(startCheckpoint(rentalId, "RCV").andExpect(status().isCreated()).andReturn());
        // 실행: 수령 봉인 이벤트를 넣는다
        publish(AmqpConfig.RECORD_SEALED, rentalId, rcv);
        // 확인: 두 번째 시점(수령)이 봉인될 때까지 기다린다
        awaitStatus(rentalId, 1, "SEALED");
        // 실행: 반납을 시작해 201을 확인하고 반납 시점 ID를 받는다
        String ret = checkpointId(startCheckpoint(rentalId, "RET").andExpect(status().isCreated()).andReturn());
        // 실행: 반납 봉인 이벤트를 넣는다
        publish(AmqpConfig.RECORD_SEALED, rentalId, ret);
        // (도구) await().atMost(시간).untilAsserted(검사) = 검사가 통과할 때까지 정해진 시간 안에서 되풀이해 본다 (Awaitility)
        // 확인: 최대 10초 동안
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                // 렌탈을 조회해서
                mvc.perform(get("/api/v1/rentals/" + rentalId).with(admin()))
                        // 상태가 반납 완료(RETURNED)가 되는지 본다
                        .andExpect(jsonPath("$.status").value("RETURNED")));
        // 반납된 렌탈은 부스가 status를 바꿔 보내도 목록에 안 나온다(OPEN 고정)
        // 실행: 부스 토큰으로 status=RETURNED 조건을 붙여 목록을 조회한다
        mvc.perform(get("/api/v1/rentals?status=RETURNED").with(booth()))
                // 확인: 이 렌탈은 목록에 없어야 한다 (부스는 조건과 관계없이 진행 중만 봄, [?(...)] = 조건에 맞는 항목만 고르는 문법)
                .andExpect(jsonPath("$.items[?(@.rentalId == '" + rentalId + "')]").isEmpty());

        // 확인: 종료하면 CLOSED가 되고 남은 볼 수 없는지 본다 (아래 도우미)
        assertClosesAfterReturn(rentalId);
        // 공개 보유 기기 목록(11/8): 로그인 없이, 봉인된 세 시점이 최근 것부터, 시리얼 없이
        // 실행: 로그인 없이 공개 보유 기기 목록을 조회한다
        mvc.perform(get("/api/v1/catalog/devices"))
                // 확인: 응답 코드가 200이어야 한다
                .andExpect(status().isOk())
                // 확인: 이 기기가 목록에 한 번만 있어야 한다 (hasSize(1) = 개수 1)
                .andExpect(jsonPath("$.items[?(@.deviceId == '" + deviceId + "')].deviceId").value(hasSize(1)))
                // 확인: 이 기기의 첫 시점은 가장 최근인 반납 시점이어야 한다
                .andExpect(jsonPath("$.items[?(@.deviceId == '" + deviceId + "')].checkpoints[0].checkpointId").value(ret))
                // 확인: 렌탈이 종료됐으니 대여 가능이어야 한다
                .andExpect(jsonPath("$.items[?(@.deviceId == '" + deviceId + "')].available").value(true))
                // 확인: 시리얼은 공개 목록에 없어야 한다
                .andExpect(jsonPath("$.items[?(@.deviceId == '" + deviceId + "')].serial").doesNotExist());

        // 실행+확인: 종료 뒤에는 같은 기기로 새 렌탈을 만들 수 있어야 한다 (도우미 안에서 201 확인)
        openRental(deviceId);
    }

    /** 시점 시작 이벤트(trace.checkpoint.started 1.1)가 기종·시리얼을 싣고 한 번만 나갔는지 본다. */
    private void assertStartedOnce(Queue started, String rentalId, String checkpointId, String deviceId)
            throws Exception {
        // 시험용 큐에서 메시지 하나를 최대 10초 기다려 꺼낸다
        Message startedMessage = rabbit.receive(started.getName(), 10_000);
        // 메시지가 왔어야 한다 (null이면 이벤트가 안 나간 것)
        assertThat(startedMessage).isNotNull();
        // 메시지 본문 JSON을 읽는다
        JsonNode startedEvent = json.readTree(startedMessage.getBody());
        // 메시지 ID 머리말이 본문의 eventId와 같아야 한다 (받는 쪽이 중복을 거를 수 있게)
        assertThat(startedMessage.getMessageProperties().getMessageId()).isEqualTo(startedEvent.get("eventId").asText());
        // 이벤트 형식 버전이 1.1이어야 한다
        assertThat(startedEvent.get("schemaVersion").asText()).isEqualTo("1.1");
        // 렌탈 ID가 맞아야 한다
        assertThat(startedEvent.get("rentalId").asText()).isEqualTo(rentalId);
        // 시점 ID가 맞아야 한다
        assertThat(startedEvent.get("checkpointId").asText()).isEqualTo(checkpointId);
        // 시점 종류가 출고(OUT)여야 한다
        assertThat(startedEvent.get("checkpointType").asText()).isEqualTo("OUT");
        // 기기 ID가 맞아야 한다
        assertThat(startedEvent.get("deviceId").asText()).isEqualTo(deviceId);
        // 시리얼이 실려 있어야 한다
        assertThat(startedEvent.get("serial").asText()).isEqualTo("SER-FLOW-1");
        // 기종 코드가 실려 있어야 한다
        assertThat(startedEvent.get("modelCode").asText()).isEqualTo("FLOW-1");
        // 기종 이름이 비어 있지 않아야 한다 (isNotBlank = 공백뿐이 아님)
        assertThat(startedEvent.get("modelName").asText()).isNotBlank();
        // 0.5초 더 기다려도 두 번째 메시지는 없어야 한다 (거절된 RCV 시작은 이벤트를 안 냄 = 정확히 한 번)
        assertThat(rabbit.receive(started.getName(), 500)).isNull();
    }

    /** 1.0.x 추가 필드: 상세에 시리얼, 목록에 지금 시점(트랜잭션 안에서 시점을 읽는지 실제 DB로 확인). */
    private void assertOutCheckpointListed(String rentalId) throws Exception {
        // 관리자로 렌탈 상세를 조회해서
        mvc.perform(get("/api/v1/rentals/" + rentalId).with(admin()))
                // 상세에 기기 시리얼이 있어야 한다
                .andExpect(jsonPath("$.serial").value("SER-FLOW-1"));
        // 관리자로 렌탈 목록을 조회해서
        mvc.perform(get("/api/v1/rentals").with(admin()))
                // 이 렌탈의 지금 시점이 출고여야 한다
                .andExpect(jsonPath("$.items[?(@.rentalId == '" + rentalId + "')].currentCheckpoint.type").value("OUT"));
        // 부스 태블릿 기기 목록: 부스 토큰은 진행 중 렌탈을 rentals 클레임 없이 본다
        // 부스 토큰으로 렌탈 목록을 조회해서
        mvc.perform(get("/api/v1/rentals").with(booth()))
                // 부스에게도 이 렌탈과 지금 시점(출고)이 보여야 한다
                .andExpect(jsonPath("$.items[?(@.rentalId == '" + rentalId + "')].currentCheckpoint.type").value("OUT"));
    }

    /** 세 시점이 끝나 RETURNED인 렌탈을 닫으면 CLOSED가 되고, 다른 고객은 볼 수 없다. */
    private void assertClosesAfterReturn(String rentalId) throws Exception {
        // 관리자로 렌탈 상세를 조회해서
        mvc.perform(get("/api/v1/rentals/" + rentalId).with(admin()))
                // 시점이 3개여야 하고
                .andExpect(jsonPath("$.checkpoints.length()").value(3))
                // 첫 시점의 발생 시각이 채워져 있어야 하고
                .andExpect(jsonPath("$.checkpoints[0].occurredAt").isNotEmpty())
                // 렌탈 시작 시각도 채워져 있어야 한다
                .andExpect(jsonPath("$.startedAt").isNotEmpty());
        // 반납 완료 렌탈을 종료하면 204여야 한다
        mvc.perform(post("/api/v1/rentals/" + rentalId + "/close").with(admin())).andExpect(status().isNoContent());
        // 이 렌탈의 고객 토큰으로 조회해서
        mvc.perform(get("/api/v1/rentals/" + rentalId).with(customer(rentalId)))
                // 상태가 종료(CLOSED)여야 하고
                .andExpect(jsonPath("$.status").value("CLOSED"))
                // 종료 시각이 채워져 있어야 한다
                .andExpect(jsonPath("$.endedAt").isNotEmpty());
        // 이 렌탈이 없는 다른 고객이 조회하면 403이어야 한다
        mvc.perform(get("/api/v1/rentals/" + rentalId).with(customer())).andExpect(status().isForbidden());
    }

    // [상황] 수리 중·폐기 기기로 렌탈을 만들고, 진행 중 렌탈이 있는 기기의 상태를 바꾸려 한다.
    // [기대] 렌탈 만들기는 409. 폐기해도 기기 기록은 지워지지 않는다(이력 보존). 진행 중 렌탈이 있으면 상태 변경 불가.
    //        ※ 응답 코드: 409 = 지금 상태와 맞지 않아 거절(이미 있음·순서 어긋남 등)
    @Test
    @DisplayName("수리 중·폐기 기기로 렌탈을 만들면 409이고 폐기해도 행은 남는다, 진행 중 렌탈이 있으면 상태를 못 바꾼다")
    void retiredDeviceKeepsRow() throws Exception {
        // 준비: 시험용 기종 RET-1을 저장한다
        upsertModel("RET-1");
        // 준비: 렌탈 중으로 만들 기기를 등록한다
        String busy = registerDevice("SER-RET-BUSY", "RET-1");
        // 준비: 그 기기로 렌탈을 연다
        openRental(busy);
        // 실행: 렌탈 중인 기기를 폐기(RETIRED)로 바꾸려 한다
        mvc.perform(patch("/api/v1/devices/" + busy).with(admin()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"RETIRED\"}"))
                // 확인: 409이고 에러 코드가 DEVICE_HAS_OPEN_RENTAL이어야 한다
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DEVICE_HAS_OPEN_RENTAL"));

        // 준비: 폐기해 볼 다른 기기를 등록한다
        String deviceId = registerDevice("SER-RET-1", "RET-1");
        // 실행: 그 기기를 폐기하면서 색도 지운다
        mvc.perform(patch("/api/v1/devices/" + deviceId).with(admin()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"RETIRED\",\"color\":null}"))
                // 확인: 응답 코드가 200이어야 한다
                .andExpect(status().isOk())
                // 확인: 상태가 폐기여야 한다
                .andExpect(jsonPath("$.status").value("RETIRED"))
                // 확인: 폐기 시각이 채워져야 한다
                .andExpect(jsonPath("$.retiredAt").isNotEmpty())
                // 확인: 색이 지워져 있어야 한다
                .andExpect(jsonPath("$.color").isEmpty());
        // 실행: 폐기한 기기로 렌탈을 만들어 본다
        mvc.perform(post("/api/v1/rentals").with(admin()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"deviceId\":\"" + deviceId + "\",\"renterRef\":\"R\"}"))
                // 확인: 409이고 에러 코드가 DEVICE_NOT_ACTIVE여야 한다
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DEVICE_NOT_ACTIVE"));
        // 실행: 폐기 상태·기종 RET-1로 기기 목록을 조회한다
        mvc.perform(get("/api/v1/devices?status=RETIRED&modelCode=RET-1").with(admin()))
                // 확인: 1대가 나와야 한다 (폐기해도 행이 지워지지 않음)
                .andExpect(jsonPath("$.items.length()").value(1))
                // 확인: 그 1대가 방금 폐기한 기기여야 한다
                .andExpect(jsonPath("$.items[0].deviceId").value(deviceId));
        // 확인: DB의 마지막 변경자(updated_by)가 테스트 관리자 ID여야 한다
        assertThat(jdbc.queryForObject("select updated_by from rental.device where public_id = ?", String.class,
                deviceId)).isEqualTo("admin0000000000000001");
    }

    // [상황] 폐기 기기를 "사용 가능"으로 바로 바꾸고, "수리 중"으로도 되돌려 본다.
    // [기대] 바로 사용 가능은 409. 수리 중으로는 되돌릴 수 있고 폐기 시각이 지워진다.
    //        ※ 응답 코드: 409 = 지금 상태와 맞지 않아 거절(이미 있음·순서 어긋남 등)
    @Test
    @DisplayName("폐기 기기는 사용 가능으로 바로 못 바꾸고(409), 수리 중으로는 되돌리며 retiredAt이 비워진다")
    void retiredDeviceTransitions() throws Exception {
        // 준비: 시험용 기종 RET-2를 저장한다
        upsertModel("RET-2");
        // 준비: 기기를 등록한다
        String deviceId = registerDevice("SER-RET-2", "RET-2");
        // 준비: 기기를 폐기한다
        mvc.perform(patch("/api/v1/devices/" + deviceId).with(admin()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"RETIRED\"}"))
                // 준비: 폐기가 성공(200)했는지 본다
                .andExpect(status().isOk());

        // 실행: 폐기 기기를 바로 사용 가능(ACTIVE)으로 바꾸려 한다
        mvc.perform(patch("/api/v1/devices/" + deviceId).with(admin()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"ACTIVE\"}"))
                // 확인: 응답 코드가 409여야 한다
                .andExpect(status().isConflict())
                // 확인: 에러 코드가 DEVICE_STATUS_TRANSITION_INVALID여야 한다
                .andExpect(jsonPath("$.code").value("DEVICE_STATUS_TRANSITION_INVALID"));
        // 확인: DB에서도 상태가 폐기 그대로여야 한다
        assertThat(jdbc.queryForObject("select status from rental.device where public_id = ?", String.class,
                deviceId)).isEqualTo("RETIRED");

        // 실행: 폐기 기기를 수리 중(MAINTENANCE)으로 되돌린다
        mvc.perform(patch("/api/v1/devices/" + deviceId).with(admin()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"MAINTENANCE\"}"))
                // 확인: 응답 코드가 200이어야 한다
                .andExpect(status().isOk())
                // 확인: 상태가 수리 중이어야 한다
                .andExpect(jsonPath("$.status").value("MAINTENANCE"))
                // 확인: 폐기 시각이 지워져 있어야 한다
                .andExpect(jsonPath("$.retiredAt").isEmpty());
        // 실행: 수리 중 기기를 사용 가능으로 바꾼다
        mvc.perform(patch("/api/v1/devices/" + deviceId).with(admin()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"ACTIVE\"}"))
                // 확인: 응답 코드가 200이어야 한다
                .andExpect(status().isOk())
                // 확인: 상태가 사용 가능이어야 한다
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    // [상황] 일련번호 TEST-로 시작하는 시험 렌탈을 관리자가 지우고, 실제 렌탈도 지워 본다.
    // [기대] 시험 렌탈은 렌탈·시점·기기가 사라지고 시점마다 "시험 시점 지움" 이벤트가 나간다(다른 서버도 지우게).
    //        같은 일련번호로 다시 등록된다. 실제 렌탈은 409로 지우지 않는다.
    //        ※ 응답 코드: 409 = 지금 상태와 맞지 않아 거절(이미 있음·순서 어긋남 등)
    @Test
    @DisplayName("시험 렌탈(TEST-)을 지우면 렌탈·시점·기기가 사라지고 시점마다 purged가 나가며, 같은 시리얼로 다시 등록된다. 실제 렌탈은 409")
    void deleteTestRental() throws Exception {
        // 준비: 시험용 기종 PURGE-1을 저장한다
        upsertModel("PURGE-1");
        // 준비: 시험 기기(TEST-로 시작)를 등록한다
        String deviceId = registerDevice("TEST-PURGE-1", "PURGE-1");
        // 준비: 그 기기로 렌탈을 연다
        String rentalId = openRental(deviceId);
        // 준비: 출고를 시작하고 출고 시점 ID를 받는다
        String out = checkpointId(startCheckpoint(rentalId, "OUT").andExpect(status().isCreated()).andReturn());
        // 준비: "시점 지움" 이벤트를 받아 볼 시험용 큐를 만든다
        Queue purged = new Queue("test.checkpoint.purged", false, false, false);
        // 준비: 메시지 큐 서버에 그 큐를 만든다
        amqpAdmin.declareQueue(purged);
        // 준비: 큐에 남은 옛 메시지를 비운다
        amqpAdmin.purgeQueue(purged.getName(), false);
        // 준비: trace.checkpoint.purged 이벤트가 이 큐로 오게 연결한다
        amqpAdmin.declareBinding(BindingBuilder.bind(purged).to(new TopicExchange(AmqpConfig.EXCHANGE))
                .with("trace.checkpoint.purged"));

        // 실행+확인: 관리자가 시험 렌탈을 지우면 204여야 한다
        mvc.perform(delete("/api/v1/rentals/" + rentalId).with(admin())).andExpect(status().isNoContent());
        // 확인: 지운 렌탈을 조회하면 404여야 한다
        mvc.perform(get("/api/v1/rentals/" + rentalId).with(admin())).andExpect(status().isNotFound());
        // 확인: DB에서 그 기기 행도 0개여야 한다 (기기까지 지워짐)
        assertThat(jdbc.queryForObject("select count(*) from rental.device where public_id = ?", Integer.class, deviceId))
                .isZero();
        // 확인: 시험용 큐에서 메시지를 최대 10초 기다려 꺼낸다
        Message message = rabbit.receive(purged.getName(), 10_000);
        // 확인: 메시지가 왔어야 한다
        assertThat(message).isNotNull();
        // 확인: 메시지 본문 JSON을 읽는다
        JsonNode event = json.readTree(message.getBody());
        // 확인: 이벤트의 시점 ID가 지운 출고 시점이어야 한다
        assertThat(event.get("checkpointId").asText()).isEqualTo(out);
        // 확인: 이벤트에 시험 기기 시리얼이 실려야 한다
        assertThat(event.get("serial").asText()).isEqualTo("TEST-PURGE-1");
        // 확인: 시점이 1개였으니 두 번째 메시지는 없어야 한다
        assertThat(rabbit.receive(purged.getName(), 500)).isNull();
        // 실행+확인: 같은 시리얼로 다시 등록할 수 있어야 한다 (도우미 안에서 201 확인)
        registerDevice("TEST-PURGE-1", "PURGE-1");

        // 준비: 실제 기기(TEST-가 아님)를 등록하고 렌탈을 연다
        String real = openRental(registerDevice("SER-PURGE-2", "PURGE-1"));
        // 실행: 실제 렌탈을 지워 본다
        mvc.perform(delete("/api/v1/rentals/" + real).with(admin()))
                // 확인: 409이고 에러 코드가 NOT_TEST_DATA여야 한다
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("NOT_TEST_DATA"));
        // 확인: 실제 렌탈은 그대로 조회돼야 한다
        mvc.perform(get("/api/v1/rentals/" + real).with(admin())).andExpect(status().isOk());
    }

    // [상황] 처음 DB를 만들 때 넣는 기종 기본값(맥북 에어 13 M2)을 조회하고, 기본값 넣기를 다시 돌린다.
    // [기대] 가로·세로·본체 두께·덮개 두께 4개 치수와 함께 조회되고, 다시 돌려도 한 건뿐이다.
    @Test
    @DisplayName("기종 시드 V2: MBA13-M2가 치수 4개와 함께 조회되고, 시드를 다시 돌려도 한 건이다")
    void seedDeviceModel() throws Exception {
        // 실행: DB 처음 만들 때 넣은 기본 기종 MBA13-M2를 조회한다
        mvc.perform(get("/api/v1/device-models/MBA13-M2").with(admin()))
                // 확인: 응답 코드가 200이어야 한다
                .andExpect(status().isOk())
                // 확인: 기종 이름이 맞아야 한다
                .andExpect(jsonPath("$.name").value("MacBook Air (M2, 2022)"))
                // 확인: 제조사가 Apple이어야 한다
                .andExpect(jsonPath("$.manufacturer").value("Apple"))
                // 확인: 형태가 CLAMSHELL이어야 한다
                .andExpect(jsonPath("$.formFactor").value("CLAMSHELL"))
                // 확인: CPU가 Apple M2여야 한다
                .andExpect(jsonPath("$.spec.cpu").value("Apple M2"))
                // 확인: 가로 304.1mm
                .andExpect(jsonPath("$.dimensions.widthMm").value(304.1))
                // 확인: 세로 215.0mm
                .andExpect(jsonPath("$.dimensions.depthMm").value(215.0))
                // 확인: 본체 두께 7.0mm
                .andExpect(jsonPath("$.dimensions.baseHMm").value(7.0))
                // 확인: 덮개 두께 4.0mm
                .andExpect(jsonPath("$.dimensions.lidHMm").value(4.0));

        // 실행: 기본값을 넣는 SQL 파일(V2__seed_device_models.sql)을 글자로 읽는다
        String seed = new String(getClass().getResourceAsStream("/db/migration/V2__seed_device_models.sql")
                .readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        // 실행: 그 SQL을 한 번 더 실행한다
        jdbc.execute(seed);
        // 확인: MBA13-M2는 여전히 1건이어야 한다 (다시 돌려도 중복이 안 생김)
        assertThat(jdbc.queryForObject("select count(*) from rental.device_model where model_code = 'MBA13-M2'",
                Integer.class)).isEqualTo(1);
    }

    // [상황] 부스 코드(관리자가 발급하는 6자리 숫자, 부스 태블릿이 이걸로 어떤 렌탈을 찍을지 고름) 흐름 전체.
    // [기대] 출고 시점 전 발급은 409. 발급한 코드로 부스가 기기·렌탈·시점 ID를 한 번만 받는다.
    //        다시 발급하면 앞 코드는 못 쓰고, 이미 촬영한 시점의 코드는 410(만료).
    //        ※ 응답 코드: 409 = 지금 상태와 맞지 않아 거절(이미 있음·순서 어긋남 등), 410 = 이미 쓰였거나 만료되어 더는 쓸 수 없음
    @Test
    @DisplayName("부스 코드: 출고 전 발급은 409, 발급한 코드로 세 ID를 한 번만 받고, 다시 발급하면 앞 코드는 못 쓰며, 찍은 시점의 코드는 410")
    void boothCodeFlow() throws Exception {
        // 준비: 시험용 기종 CODE-1을 저장한다
        upsertModel("CODE-1");
        // 준비: 시험 기기를 등록한다
        String deviceId = registerDevice("TEST-CODE-1", "CODE-1");
        // 준비: 렌탈을 연다
        String rentalId = openRental(deviceId);
        // 실행: 출고 시점을 시작하기 전에 부스 코드를 발급해 본다
        mvc.perform(post("/api/v1/rentals/" + rentalId + "/booth-code").with(admin()))
                // 확인: 409이고 에러 코드가 CHECKPOINT_ORDER_VIOLATION이어야 한다 (찍을 시점이 없음)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CHECKPOINT_ORDER_VIOLATION"));
        // 준비: 출고를 시작하고 출고 시점 ID를 받는다
        String out = checkpointId(startCheckpoint(rentalId, "OUT").andExpect(status().isCreated()).andReturn());

        // 실행: 부스 코드를 발급한다 (첫 번째)
        String first = issueBoothCode(rentalId, out);
        // 실행: 다시 발급한다 (두 번째, 첫 번째는 무효가 됨)
        String second = issueBoothCode(rentalId, out);
        // 확인: 첫 번째 코드를 넣으면 410이고 에러 코드가 BOOTH_CODE_EXPIRED여야 한다
        redeem(first).andExpect(status().isGone()).andExpect(jsonPath("$.code").value("BOOTH_CODE_EXPIRED"));
        // 실행: 두 번째 코드를 넣고, 200이어야 한다
        redeem(second).andExpect(status().isOk())
                // 확인: 렌탈 ID가 맞아야 한다
                .andExpect(jsonPath("$.rentalId").value(rentalId))
                // 확인: 기기 ID가 맞아야 한다
                .andExpect(jsonPath("$.deviceId").value(deviceId))
                // 확인: 시리얼이 맞아야 한다
                .andExpect(jsonPath("$.serial").value("TEST-CODE-1"))
                // 확인: 기종 코드가 맞아야 한다
                .andExpect(jsonPath("$.modelCode").value("CODE-1"))
                // 확인: 찍을 시점 ID가 출고 시점이어야 한다
                .andExpect(jsonPath("$.checkpointId").value(out))
                // 확인: 찍을 시점 종류가 출고여야 한다
                .andExpect(jsonPath("$.checkpointType").value("OUT"));
        // 실행+확인: 같은 코드를 또 넣으면 410이어야 한다 (한 번만 쓸 수 있음)
        redeem(second).andExpect(status().isGone());
        // 확인: DB에는 코드의 SHA-256 해시로 저장된 행이 1개 있어야 한다
        assertThat(jdbc.queryForObject("select count(*) from rental.booth_code where code_hash = ?", Integer.class,
                sha256(second))).isEqualTo(1);
        // 확인: 코드 원문 그대로 저장된 행은 0개여야 한다 (DB가 새어도 코드를 알 수 없게)
        assertThat(jdbc.queryForObject("select count(*) from rental.booth_code where code_hash = ?", Integer.class,
                second)).isZero(); // 원문은 저장하지 않는다

        // 준비: 세 번째 코드를 발급한다
        String third = issueBoothCode(rentalId, out);
        // 실행: 출고 시점에 "사진 업로드됨" 이벤트를 넣는다 (이미 찍은 시점이 됨)
        publish(AmqpConfig.CAPTURE_UPLOADED, rentalId, out);
        // 확인: 출고가 촬영됨이 될 때까지 기다린다
        awaitStatus(rentalId, 0, "CAPTURED");
        // 실행+확인: 이미 찍은 시점의 코드를 넣으면 410이어야 한다
        redeem(third).andExpect(status().isGone());
        // 실행: 찍은 시점에 코드를 또 발급해 본다
        mvc.perform(post("/api/v1/rentals/" + rentalId + "/booth-code").with(admin()))
                // 확인: 409여야 한다
                .andExpect(status().isConflict());

        // 시험 렌탈을 지우면 코드도 함께 지워진다(외래 키 ON DELETE CASCADE)
        // 실행+확인: 시험 렌탈을 지우면 204여야 한다
        mvc.perform(delete("/api/v1/rentals/" + rentalId).with(admin())).andExpect(status().isNoContent());
        // 확인: 부스 코드 행도 모두 지워져 0개여야 한다
        assertThat(jdbc.queryForObject("select count(*) from rental.booth_code", Integer.class)).isZero();
    }

    private String issueBoothCode(String rentalId, String checkpointId) throws Exception {
        // 관리자로 부스 코드를 발급하고
        MvcResult result = mvc.perform(post("/api/v1/rentals/" + rentalId + "/booth-code").with(admin()))
                // 201이어야 하고
                .andExpect(status().isCreated())
                // 묶인 시점 ID가 맞아야 하고
                .andExpect(jsonPath("$.checkpointId").value(checkpointId))
                // 시점 종류가 출고여야 하며
                .andExpect(jsonPath("$.checkpointType").value("OUT"))
                // 응답 전체를 받아 둔다
                .andReturn();
        // 응답 JSON에서 code 값을 꺼낸다
        String code = json.readTree(result.getResponse().getContentAsString()).path("code").asText();
        // 코드는 숫자 6자리여야 한다
        assertThat(code).matches("[0-9]{6}");
        // 코드를 돌려준다
        return code;
    }

    private org.springframework.test.web.servlet.ResultActions redeem(String code) throws Exception {
        // 부스 토큰으로 코드를 JSON 본문에 담아 코드 넣기 요청을 보내고, 응답 검사를 이어 붙일 수 있게 돌려준다
        return mvc.perform(post("/api/v1/rentals/booth-codes/redeem").with(booth()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"" + code + "\"}"));
    }

    private static String sha256(String value) throws Exception {
        // 글자를 SHA-256으로 해시해 16진수 글자로 돌려준다 (서버가 DB에 저장하는 방식과 같게)
        return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    // [상황] 같은 일련번호로 기기를 두 번 등록하고, 없는 기종으로 등록하고, 기종 이름으로 치수를 조회한다.
    // [기대] 중복 409, 없는 기종 404, 치수는 3D 화면용으로 조회된다.
    //        ※ 응답 코드: 409 = 지금 상태와 맞지 않아 거절(이미 있음·순서 어긋남 등), 404 = 찾을 수 없음(없는 주소나 데이터)
    @Test
    @DisplayName("시리얼 중복은 409, 없는 기종은 404, 모델명으로 3D 트윈 치수를 조회한다")
    void deviceAndModelRules() throws Exception {
        // 준비: 시험용 기종 DIM-1을 저장한다
        upsertModel("DIM-1");
        // 준비: 시리얼 SER-DUP-1 기기를 등록한다
        registerDevice("SER-DUP-1", "DIM-1");
        // 실행: 같은 시리얼로 한 번 더 등록한다
        mvc.perform(post("/api/v1/devices").with(admin()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"serial\":\"SER-DUP-1\",\"modelCode\":\"DIM-1\",\"os\":\"MACOS\"}"))
                // 확인: 409이고 에러 코드가 DEVICE_SERIAL_EXISTS여야 한다
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DEVICE_SERIAL_EXISTS"));
        // 실행: 없는 기종 NONE-1로 기기를 등록한다
        mvc.perform(post("/api/v1/devices").with(admin()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"serial\":\"SER-X\",\"modelCode\":\"NONE-1\",\"os\":\"MACOS\"}"))
                // 확인: 404여야 한다
                .andExpect(status().isNotFound());
        // 실행: 고객 토큰으로 기종 DIM-1을 조회한다
        mvc.perform(get("/api/v1/device-models/DIM-1").with(customer()))
                // 확인: 고객도 볼 수 있어 200이어야 한다
                .andExpect(status().isOk())
                // 확인: 가로 치수가 304.1mm여야 한다
                .andExpect(jsonPath("$.dimensions.widthMm").value(304.1))
                // 확인: CPU가 Apple M2여야 한다
                .andExpect(jsonPath("$.spec.cpu").value("Apple M2"));
    }

    // [상황] 기기 목록을 기종·상태·일련번호 일부로 찾고 여러 쪽으로 나눠 넘긴다.
    // [기대] 조건대로 걸러지고, 쪽을 넘겨도 같은 기기가 두 번 나오거나 빠지지 않는다.
    @Test
    @DisplayName("기기 목록은 기종·상태·시리얼 일부로 찾고 커서로 겹침 없이 넘긴다")
    void devicePaging() throws Exception {
        // 준비: 시험용 기종 PAGE-1을 저장한다
        upsertModel("PAGE-1");
        // 준비: 5번 되풀이한다
        for (int i = 0; i < 5; i++) {
            // 준비: 시리얼 PG-SER-0 ~ PG-SER-4 기기를 하나씩 등록한다
            registerDevice("PG-SER-" + i, "PAGE-1");
        }
        // 실행: 쪽을 넘기며 본 시리얼을 모을 목록
        List<String> seen = new ArrayList<>();
        // 실행: 다음 쪽 커서 (처음엔 없음 = 첫 쪽)
        String cursor = null;
        // 실행: 다음 쪽이 없을 때까지 되풀이한다
        do {
            // 기종 PAGE-1, 시리얼에 pg-ser가 들어간 것, 한 쪽에 2개씩, 커서가 있으면 붙인 주소를 만든다
            String url = "/api/v1/devices?modelCode=PAGE-1&q=pg-ser&limit=2" + (cursor == null ? "" : "&cursor=" + cursor);
            // 관리자로 그 주소를 조회해 200을 확인하고 응답 JSON을 읽는다
            JsonNode page = json.readTree(mvc.perform(get(url).with(admin())).andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString());
            // 이번 쪽의 기기 시리얼을 모두 목록에 모은다
            page.path("items").forEach(item -> seen.add(item.path("serial").asText()));
            // 다음 쪽 커서를 꺼낸다 (null이면 마지막 쪽)
            cursor = page.path("nextCursor").isNull() ? null : page.path("nextCursor").asText();
        } while (cursor != null);
        // 확인: 모은 시리얼이 최근 등록 순으로 5개가 겹침·빠짐 없이 정확히 나와야 한다
        assertThat(seen).containsExactly("PG-SER-4", "PG-SER-3", "PG-SER-2", "PG-SER-1", "PG-SER-0");
    }

    // [상황] 기기 하나의 렌탈 이력을 본다(상태 거르기 포함).
    // [기대] 그 기기의 렌탈만 최근 순으로 나오고, 기기 상세는 관리자에게 보인다.
    @Test
    @DisplayName("기기별 렌탈 이력: deviceId로 그 기기 렌탈만 최근 순으로, status와 겹쳐 거르고, 기기 상세는 ADMIN에게 보인다")
    void deviceRentalHistory() throws Exception {
        // 준비: 시험용 기종 HIST-1을 저장한다
        upsertModel("HIST-1");
        // 준비: 볼 기기 A를 등록한다
        String mine = registerDevice("SER-HIST-A", "HIST-1");
        // 준비: 다른 기기 B를 등록한다
        String other = registerDevice("SER-HIST-B", "HIST-1");
        // 준비: 기기 A로 렌탈을 연다
        String rental = openRental(mine);
        // 준비: 기기 B로도 렌탈을 연다 (걸러지는지 보려고)
        openRental(other);
        // 실행: 기기 A로 걸러 렌탈 목록을 조회한다
        mvc.perform(get("/api/v1/rentals?deviceId=" + mine).with(admin()))
                // 확인: 응답 코드가 200이어야 한다
                .andExpect(status().isOk())
                // 확인: 1건만 나와야 한다 (기기 B 렌탈은 빠짐)
                .andExpect(jsonPath("$.items.length()").value(1))
                // 확인: 그 1건이 기기 A의 렌탈이어야 한다
                .andExpect(jsonPath("$.items[0].rentalId").value(rental))
                // 확인: 대여자 참조가 R-1이어야 한다
                .andExpect(jsonPath("$.items[0].renterRef").value("R-1"))
                // 확인: 출고 전이니 시작 시각은 비어 있어야 한다
                .andExpect(jsonPath("$.items[0].startedAt").isEmpty())
                // 확인: 종료 전이니 종료 시각도 비어 있어야 한다
                .andExpect(jsonPath("$.items[0].endedAt").isEmpty());
        // 실행: 기기 A + 종료(CLOSED) 조건을 겹쳐 조회한다
        mvc.perform(get("/api/v1/rentals?deviceId=" + mine + "&status=CLOSED").with(admin()))
                // 확인: 종료된 렌탈이 없으니 0건이어야 한다
                .andExpect(jsonPath("$.items.length()").value(0));
        // 실행: 없는 기기 ID(새로 만든 아무 ID)로 조회한다
        mvc.perform(get("/api/v1/rentals?deviceId=" + PublicIds.next()).with(admin()))
                // 확인: 0건이어야 한다
                .andExpect(jsonPath("$.items.length()").value(0));
        // 실행: 관리자로 기기 A 상세를 조회한다
        mvc.perform(get("/api/v1/devices/" + mine).with(admin()))
                // 확인: 응답 코드가 200이어야 한다
                .andExpect(status().isOk())
                // 확인: 시리얼이 SER-HIST-A여야 한다
                .andExpect(jsonPath("$.serial").value("SER-HIST-A"));
        // 실행: 없는 기기 ID로 상세를 조회한다
        mvc.perform(get("/api/v1/devices/" + PublicIds.next()).with(admin()))
                // 확인: 404여야 한다
                .andExpect(status().isNotFound());
    }

    // [상황] 관리자 화면에서 "문제 있는 검사만 보기"를 켠다.
    // [기대] 지금 시점 등급이 B·C·D(흠집 있음)인 렌탈만 나오고 전체 건수도 같은 조건으로 센다.
    //        다음 시점을 시작하면 목록에서 빠진다.
    @Test
    @DisplayName("문제 있는 검사만 보기: 지금 시점 등급이 B·C·D인 렌탈만 나오고 totalCount가 같은 조건의 건수다. 다음 시점을 시작하면 빠진다")
    void problemFilterAndTotalCount() throws Exception {
        // 준비: 시험용 기종 PROB-1을 저장한다
        upsertModel("PROB-1");
        // 준비: 기기를 등록한다
        String deviceId = registerDevice("SER-PROB-1", "PROB-1");
        // 준비: 렌탈을 연다
        String rentalId = openRental(deviceId);
        // 준비: 출고를 시작하고 출고 시점 ID를 받는다
        String out = checkpointId(startCheckpoint(rentalId, "OUT").andExpect(status().isCreated()).andReturn());
        // 준비: 이 기기 렌탈 중 "문제 있는 것만" 보는 목록 주소를 만든다
        String onlyThis = "/api/v1/rentals?deviceId=" + deviceId + "&problem=true";
        // 실행: 등급이 아직 없을 때 그 목록을 조회한다
        mvc.perform(get(onlyThis).with(admin()))
                // 확인: 0건이고 전체 건수도 0이어야 한다
                .andExpect(jsonPath("$.items.length()").value(0)).andExpect(jsonPath("$.totalCount").value(0));

        // 실행: 출고 시점에 등급 C(문제 있음) 이벤트를 넣는다
        publishGraded(rentalId, out, "C");
        // 확인: 최대 10초 동안 목록을 다시 조회해서
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> mvc.perform(get(onlyThis).with(admin()))
                // 1건이 나오고
                .andExpect(jsonPath("$.items.length()").value(1))
                // 그게 이 렌탈이고
                .andExpect(jsonPath("$.items[0].rentalId").value(rentalId))
                // 전체 건수가 1이 되는지 본다
                .andExpect(jsonPath("$.totalCount").value(1)));
        // 실행: 문제 조건 없이 한 쪽 1개로 조회한다
        mvc.perform(get("/api/v1/rentals?deviceId=" + deviceId + "&limit=1").with(admin()))
                // 확인: 전체 건수가 1이어야 한다
                .andExpect(jsonPath("$.totalCount").value(1));

        // 실행: 출고 봉인 이벤트를 넣는다
        publish(AmqpConfig.RECORD_SEALED, rentalId, out);
        // 확인: 출고가 봉인될 때까지 기다린다
        awaitStatus(rentalId, 0, "SEALED");
        // 실행: 수령을 시작한다 (이제 지금 시점은 등급 없는 수령)
        String rcv = checkpointId(startCheckpoint(rentalId, "RCV").andExpect(status().isCreated()).andReturn());
        // 확인: 지금 시점(수령)에 문제 등급이 없으니 목록에서 빠져 0건이어야 한다
        mvc.perform(get(onlyThis).with(admin())).andExpect(jsonPath("$.items.length()").value(0));
        // 실행: 수령에 등급 A(문제 없음) 이벤트를 넣는다
        publishGraded(rentalId, rcv, "A");
        // 실행: 다시 검수해 등급 D 이벤트를 넣는다
        publishGraded(rentalId, rcv, "D"); // 다시 검수하면 마지막 등급으로
        // 확인: 최대 10초 동안 목록을 조회해서
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> mvc.perform(get(onlyThis).with(admin()))
                // 마지막 등급 D로 다시 1건이 나오는지 본다
                .andExpect(jsonPath("$.items.length()").value(1)));
    }

    // [상황] 렌탈을 만들 때 고객 이름·반납 예정일·관리자 메모를 넣고, 나중에 고치고, 고객이 자기 렌탈을 본다.
    // [기대] 관리자는 넣고 고칠 수 있다. 고객에게는 관리자 메모가 비어 보이고, 고객은 고칠 수 없다.
    @Test
    @DisplayName("렌탈 고객 이름·반납 예정일·메모: 생성 때 받고 PATCH로 고치며, 고객에게는 메모가 null이고 고객은 고칠 수 없다")
    void rentalDetails() throws Exception {
        // 준비: 반납 예정일 = 오늘(한국)+6일
        String due = com.gakdo.trace.rental.rental.domain.RentalDetails.today().plusDays(6).toString();
        // 준비: 바꿀 반납 예정일 = 오늘+7일
        String due2 = com.gakdo.trace.rental.rental.domain.RentalDetails.today().plusDays(7).toString();
        // 준비: 시험용 기종 DET-1을 저장한다
        upsertModel("DET-1");
        // 준비: 기기를 등록한다
        String deviceId = registerDevice("SER-DET-1", "DET-1");
        // 실행: 고객 이름(앞뒤 공백)·반납 예정일·메모를 넣어 렌탈을 만든다
        MvcResult created = mvc.perform(post("/api/v1/rentals").with(admin()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"deviceId\":\"" + deviceId + "\",\"renterRef\":\"R-1\",\"customerName\":\" 김고객 \","
                        + "\"dueAt\":\"" + due + "\",\"note\":\"충전기 함께\"}"))
                // 확인: 201이어야 한다
                .andExpect(status().isCreated())
                // 확인: 이름은 공백이 지워진 김고객이어야 한다
                .andExpect(jsonPath("$.customerName").value("김고객"))
                // 확인: 반납 예정일이 맞아야 한다
                .andExpect(jsonPath("$.dueAt").value(due))
                // 확인: 관리자에게 메모가 보여야 한다
                .andExpect(jsonPath("$.note").value("충전기 함께"))
                // 응답 전체를 받아 둔다
                .andReturn();
        // 준비: 응답에서 새 렌탈 ID를 꺼낸다
        String rentalId = json.readTree(created.getResponse().getContentAsString()).path("rentalId").asText();

        // 실행: 반납 예정일은 바꾸고 메모는 비운다 (이름은 안 보냄)
        mvc.perform(patch("/api/v1/rentals/" + rentalId).with(admin()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"dueAt\":\"" + due2 + "\",\"note\":null}"))
                // 확인: 200이어야 한다
                .andExpect(status().isOk())
                // 확인: 안 보낸 이름은 그대로여야 한다
                .andExpect(jsonPath("$.customerName").value("김고객"))
                // 확인: 반납 예정일이 새 값이어야 한다
                .andExpect(jsonPath("$.dueAt").value(due2))
                // 확인: 메모가 비워져 있어야 한다
                .andExpect(jsonPath("$.note").isEmpty());
        // 실행+확인: 메모만 "메모"로 다시 넣으면 200이어야 한다
        mvc.perform(patch("/api/v1/rentals/" + rentalId).with(admin()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"note\":\"메모\"}")).andExpect(status().isOk());
        // 준비: 어제 날짜(한국)를 만든다
        String yesterday = com.gakdo.trace.rental.rental.domain.RentalDetails.today().minusDays(1).toString();
        // 실행: 반납 예정일을 어제로 바꾸려 한다
        mvc.perform(patch("/api/v1/rentals/" + rentalId).with(admin()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"dueAt\":\"" + yesterday + "\"}"))
                // 확인: 과거 날짜라 400이어야 한다
                .andExpect(status().isBadRequest())
                // 확인: 에러 코드가 VALIDATION_FAILED여야 한다
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        // 실행: 이 렌탈의 고객 토큰으로 상세를 조회한다
        mvc.perform(get("/api/v1/rentals/" + rentalId).with(customer(rentalId)))
                // 확인: 고객 이름은 보여야 한다
                .andExpect(jsonPath("$.customerName").value("김고객"))
                // 확인: 관리자 메모는 고객에게 비어 보여야 한다
                .andExpect(jsonPath("$.note").isEmpty());
        // 실행: 관리자로 이 기기 렌탈 목록을 조회한다
        mvc.perform(get("/api/v1/rentals?deviceId=" + deviceId).with(admin()))
                // 확인: 목록에도 고객 이름이 보여야 한다
                .andExpect(jsonPath("$.items[0].customerName").value("김고객"));
        // 실행+확인: 고객이 메모를 고치려 하면 403이어야 한다
        mvc.perform(patch("/api/v1/rentals/" + rentalId).with(customer(rentalId)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"note\":\"x\"}")).andExpect(status().isForbidden());
    }

    private void publishGraded(String rentalId, String checkpointId, String grade) {
        // 등급 매김 이벤트 JSON(형식 1.3)을 만든다. %s 자리에 새 이벤트 ID·렌탈 ID·시점 ID·등급을 끼워 넣는다
        String body = """
                {"schemaVersion":"1.3","eventId":"%s","occurredAt":"2026-11-09T14:05:12+09:00",
                 "rentalId":"%s","checkpointId":"%s","grade":"%s"}
                """.formatted(PublicIds.next(), rentalId, checkpointId, grade);
        // 메시지 머리말 묶음을 만든다
        MessageProperties props = new MessageProperties();
        // 본문이 JSON이라고 표시한다
        props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        // 이벤트 교환소(trace.events)에 등급 매김 경로 이름으로 메시지를 보낸다 (다른 서버가 보낸 것처럼)
        rabbit.send(AmqpConfig.EXCHANGE, AmqpConfig.CHECKPOINT_GRADED,
                MessageBuilder.withBody(body.getBytes(java.nio.charset.StandardCharsets.UTF_8)).andProperties(props).build());
    }

    private void upsertModel(String code) throws Exception {
        // 관리자로 받은 기종 코드에 맥북 에어 M2 사양·치수를 담아 기종 저장(PUT) 요청을 보낸다
        mvc.perform(put("/api/v1/device-models/" + code).with(admin()).contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"name":"MacBook Air (M2, 2022)","manufacturer":"Apple","formFactor":"CLAMSHELL",
                         "spec":{"cpu":"Apple M2","ramGb":8,"ssdGb":256},
                         "dimensions":{"widthMm":304.1,"depthMm":215.0,"baseHMm":7.0,"lidHMm":4.3}}
                        """))
                // 200이어야 한다
                .andExpect(status().isOk());
    }

    private String registerDevice(String serial, String model) throws Exception {
        // 관리자로 받은 시리얼·기종·macOS·색 실버로 기기 등록 요청을 보내고
        MvcResult result = mvc.perform(post("/api/v1/devices").with(admin()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"serial\":\"" + serial + "\",\"modelCode\":\"" + model + "\",\"os\":\"MACOS\",\"color\":\"실버\"}"))
                // 201을 확인한 뒤 응답 전체를 받아 둔다
                .andExpect(status().isCreated()).andReturn();
        // 응답 JSON에서 기기 ID를 꺼낸다
        String id = json.readTree(result.getResponse().getContentAsString()).path("deviceId").asText();
        // 기기 ID가 올바른 21자 공개 ID여야 한다
        assertThat(PublicIds.isValid(id)).isTrue();
        // 기기 ID를 돌려준다
        return id;
    }

    private String openRental(String deviceId) throws Exception {
        // 관리자로 받은 기기 ID·대여자 참조 R-1로 렌탈 생성 요청을 보내고
        MvcResult result = mvc.perform(post("/api/v1/rentals").with(admin()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"deviceId\":\"" + deviceId + "\",\"renterRef\":\"R-1\"}"))
                // 201을 확인한 뒤 응답 전체를 받아 둔다
                .andExpect(status().isCreated()).andReturn();
        // 응답 JSON에서 렌탈 ID를 꺼내 돌려준다
        return json.readTree(result.getResponse().getContentAsString()).path("rentalId").asText();
    }

    private org.springframework.test.web.servlet.ResultActions startCheckpoint(String rentalId, String type)
            throws Exception {
        // 관리자로 받은 종류(OUT·RCV·RET)의 시점 시작 요청을 보내고, 응답 검사를 이어 붙일 수 있게 돌려준다
        return mvc.perform(post("/api/v1/rentals/" + rentalId + "/checkpoints").with(admin())
                .contentType(MediaType.APPLICATION_JSON).content("{\"type\":\"" + type + "\"}"));
    }

    private String checkpointId(MvcResult result) throws Exception {
        // 응답 JSON에서 시점 ID를 꺼내 돌려준다
        return json.readTree(result.getResponse().getContentAsString()).path("checkpointId").asText();
    }

    // 도우미: 다른 서버가 보낸 것처럼 시점 이벤트(촬영됨·등급·봉인)를 메시지 큐에 넣는다.
    private void publish(String routingKey, String rentalId, String checkpointId) {
        // 시점 이벤트 JSON(형식 1.0)을 만든다. %s 자리에 새 이벤트 ID·렌탈 ID·시점 ID를 끼워 넣는다
        String body = """
                {"schemaVersion":"1.0","eventId":"%s","occurredAt":"2026-11-09T14:05:12+09:00",
                 "rentalId":"%s","checkpointId":"%s"}
                """.formatted(PublicIds.next(), rentalId, checkpointId);
        // 메시지 머리말 묶음을 만든다
        MessageProperties props = new MessageProperties();
        // 본문이 JSON이라고 표시한다
        props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        // 이벤트 교환소에 받은 경로 이름(촬영됨·등급·봉인)으로 메시지를 보낸다
        rabbit.send(AmqpConfig.EXCHANGE, routingKey, MessageBuilder.withBody(body.getBytes()).andProperties(props).build());
    }

    private void awaitStatus(String rentalId, int index, String expected) {
        // 최대 10초 동안 되풀이하며
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                // 관리자로 렌탈을 조회해서
                mvc.perform(get("/api/v1/rentals/" + rentalId).with(admin()))
                        // index번째 시점 상태가 기대한 값이 되는지 본다 (이벤트 처리를 기다리는 도우미)
                        .andExpect(jsonPath("$.checkpoints[" + index + "].status").value(expected)));
    }
}
