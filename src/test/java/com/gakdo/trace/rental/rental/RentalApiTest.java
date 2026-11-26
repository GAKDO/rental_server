// 이 파일이 확인하는 것: 렌탈 API(만들기·조회·목록·시점 시작·종료·시험 렌탈 지우기·정보 고치기)와 누가 쓸 수 있는지.

package com.gakdo.trace.rental.rental;

import static com.gakdo.trace.rental.support.TestJwts.admin;
import static com.gakdo.trace.rental.support.TestJwts.booth;
import static com.gakdo.trace.rental.support.TestJwts.customer;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.gakdo.trace.rental.common.domain.DomainException;
import com.gakdo.trace.rental.common.domain.ErrorCode;
import com.gakdo.trace.rental.common.infra.CurrentUser;
import com.gakdo.trace.rental.rental.api.RentalController;
import com.gakdo.trace.rental.rental.application.RentalService;
import com.gakdo.trace.rental.rental.domain.Checkpoint;
import com.gakdo.trace.rental.rental.domain.CheckpointType;
import com.gakdo.trace.rental.rental.domain.Rental;
import com.gakdo.trace.rental.rental.domain.RentalDetails;
import com.gakdo.trace.rental.rental.domain.RentalStatus;
import com.gakdo.trace.rental.support.Fixtures;
import com.gakdo.trace.rental.support.WebTestBase;

// (도구) @WebMvcTest = 서버 전체 대신 지정한 컨트롤러(RentalController)만 띄우는 가벼운 API 테스트. WebTestBase로 운영 보안 규칙도 함께 띄운다
@WebMvcTest(controllers = RentalController.class)
class RentalApiTest extends WebTestBase {

    // 테스트에 쓸 21자 공개 ID. 기기 ID로 쓰고, 일부 테스트에선 렌탈 ID 자리에도 그대로 쓴다 (모양만 맞으면 되므로)
    private static final String DEVICE = "IPv6mcQOlTkKj6mOecAQt";

    // (도구) MockMvc = 진짜 네트워크 없이 서버에 HTTP 요청을 보낸 것처럼 흉내 내는 도구. @Autowired = 스프링이 만들어 넣어 준다
    @Autowired
    private MockMvc mvc;

    // (도구) @MockitoBean = 진짜 서비스 대신 가짜(목) 서비스를 스프링에 끼워 넣는다. API 입구(권한·형식·응답 모양)만 따로 시험하려고
    @MockitoBean
    private RentalService service;

    // [상황] 관리자가 렌탈을 만든다.
    // [기대] 201, 새 렌탈 주소, 상태 OPEN, 시점은 아직 없음.
    //        ※ 응답 코드: 201 = 새로 만들어짐
    // (도구) @Test = JUnit에게 "이 메서드가 테스트 하나"라고 알리는 표시. @DisplayName = 결과 화면에 보일 한글 이름
    @Test
    @DisplayName("렌탈 생성은 201, Location, 상태 OPEN, checkpoints []")
    void openRental() throws Exception {
        // 준비: 응답으로 돌려줄 렌탈(기기 S1, 대여자 참조 R-2026-0012)을 만든다
        Rental rental = Rental.open(Fixtures.device("S1"), "R-2026-0012");
        // (도구) when(가짜.메서드(조건)).thenReturn(결과) = "가짜에게 이 질문이 오면 이 결과를 돌려줘"라고 미리 정해 두는 것. eq = 정확히 이 값, any = 아무 값
        // 준비: 가짜 서비스가 이 기기·대여자 참조로 렌탈을 열면 위 렌탈을 돌려주게 한다
        when(service.open(eq(DEVICE), eq("R-2026-0012"), any())).thenReturn(rental);
        // (도구) perform(요청) = 요청을 보낸다. with(admin()) = 관리자 토큰을 붙인다. andExpect(...) = 응답이 이래야 한다는 검사
        // 실행: 관리자로 기기 ID·대여자 참조를 담은 렌탈 생성 POST 요청을 보낸다
        mvc.perform(post("/api/v1/rentals").with(admin()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"deviceId\":\"" + DEVICE + "\",\"renterRef\":\"R-2026-0012\"}"))
                // 확인: 응답 코드가 201(새로 만들어짐)이어야 한다
                .andExpect(status().isCreated())
                // 확인: 응답 헤더 Location에 새 렌탈의 주소가 있어야 한다 (header() = 응답 머리말 검사)
                .andExpect(header().string("Location", "/api/v1/rentals/" + rental.getPublicId()))
                // (도구) jsonPath("$.칸") = 응답 JSON에서 그 칸의 값을 꺼낸다
                // 확인: 응답의 렌탈 ID가 새 렌탈의 공개 ID여야 한다
                .andExpect(jsonPath("$.rentalId").value(rental.getPublicId()))
                // 확인: 상태가 진행 중(OPEN)이어야 한다
                .andExpect(jsonPath("$.status").value("OPEN"))
                // 확인: 출고 전이니 시작 시각은 비어 있어야 한다
                .andExpect(jsonPath("$.startedAt").isEmpty())
                // 확인: 시점 목록은 빈 목록([])이어야 한다
                .andExpect(jsonPath("$.checkpoints").isEmpty());
    }

    // [상황] 진행 중 렌탈이 있는 기기, 수리 중·폐기 기기로 렌탈을 만든다.
    // [기대] 각각 409 RENTAL_OPEN_EXISTS, 409 DEVICE_NOT_ACTIVE.
    //        ※ 응답 코드: 409 = 지금 상태와 맞지 않아 거절(이미 있음·순서 어긋남 등)
    @Test
    @DisplayName("같은 시리얼로 진행 중인 렌탈이 있으면 409 RENTAL_OPEN_EXISTS, 수리 중·폐기 기기면 409 DEVICE_NOT_ACTIVE")
    void openConflicts() throws Exception {
        // 준비: 가짜 서비스가 렌탈을 열면
        when(service.open(any(), any(), any()))
                // 첫 번째 부를 때는 "진행 중 렌탈 있음" 에러를 던지고
                .thenThrow(new DomainException(ErrorCode.RENTAL_OPEN_EXISTS, "있음"))
                // 두 번째 부를 때는 "쓸 수 없는 기기" 에러를 던지게 한다 (thenThrow를 이어 붙이면 부를 때마다 차례로)
                .thenThrow(new DomainException(ErrorCode.DEVICE_NOT_ACTIVE, "수리 중"));
        // 준비: 렌탈 생성 본문(기기 ID, 대여자 참조 R)을 만든다
        String body = "{\"deviceId\":\"" + DEVICE + "\",\"renterRef\":\"R\"}";
        // 실행: 첫 번째 렌탈 생성 요청을 보낸다
        mvc.perform(post("/api/v1/rentals").with(admin()).contentType(MediaType.APPLICATION_JSON).content(body))
                // 확인: 409이고 에러 코드가 RENTAL_OPEN_EXISTS여야 한다 (한 기기에 진행 중 렌탈은 하나만)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("RENTAL_OPEN_EXISTS"));
        // 실행: 같은 요청을 두 번째로 보낸다
        mvc.perform(post("/api/v1/rentals").with(admin()).contentType(MediaType.APPLICATION_JSON).content(body))
                // 확인: 409이고 에러 코드가 DEVICE_NOT_ACTIVE여야 한다
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DEVICE_NOT_ACTIVE"));
    }

    // [상황] 기기 ID 모양이 틀리거나 대여자 참조가 없다.
    // [기대] 400.
    //        ※ 응답 코드: 400 = 요청 내용이 형식에 맞지 않음
    @Test
    @DisplayName("deviceId가 PublicId 형식이 아니거나 renterRef가 없으면 400")
    void openValidation() throws Exception {
        // 실행: 기기 ID를 UUID 모양으로, 대여자 참조는 빼고 렌탈 생성 요청을 보낸다
        mvc.perform(post("/api/v1/rentals").with(admin()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"deviceId\":\"0193a1c2-0000-7000-8000-000000000501\"}"))
                // 확인: 응답 코드가 400이어야 한다
                .andExpect(status().isBadRequest())
                // 확인: 칸별 오류가 2개(기기 ID 모양, 대여자 참조 빠짐)여야 한다 (length() = 목록 길이)
                .andExpect(jsonPath("$.errors.length()").value(2));
        // (도구) verifyNoInteractions(가짜) = 그 가짜가 한 번도 불리지 않았어야 한다는 검사
        // 확인: 형식이 틀린 요청은 서비스까지 가지 않았어야 한다
        verifyNoInteractions(service);
    }

    // [상황] 렌탈을 조회한다.
    // [기대] 세 시점 상태를 출고·수령·반납 순서로 준다.
    @Test
    @DisplayName("렌탈 조회는 세 시점 상태를 OUT·RCV·RET 순으로 준다")
    void getRental() throws Exception {
        // 준비: 조회 결과로 돌려줄 렌탈을 만든다
        Rental rental = Rental.open(Fixtures.device("S1"), "R");
        // 준비: 그 렌탈의 출고 시점을 시작한다
        Checkpoint out = rental.startCheckpoint(CheckpointType.OUT);
        // 준비: 가짜 서비스가 이 렌탈 ID로 조회하면 위 렌탈을 돌려주게 한다
        when(service.get(eq(rental.getPublicId()), any())).thenReturn(rental);
        // 실행: 이 렌탈을 볼 수 있는 고객 토큰으로 렌탈 상세를 조회한다
        mvc.perform(get("/api/v1/rentals/" + rental.getPublicId()).with(customer(rental.getPublicId())))
                // 확인: 응답 코드가 200이어야 한다
                .andExpect(status().isOk())
                // 확인: 첫 시점의 ID가 방금 시작한 출고 시점 ID여야 한다
                .andExpect(jsonPath("$.checkpoints[0].checkpointId").value(out.getPublicId()))
                // 확인: 첫 시점 종류가 출고(OUT)여야 한다
                .andExpect(jsonPath("$.checkpoints[0].type").value("OUT"))
                // 확인: 첫 시점 상태가 열림(OPEN)이어야 한다
                .andExpect(jsonPath("$.checkpoints[0].status").value("OPEN"))
                // 확인: 아직 촬영 전이니 발생 시각은 비어 있어야 한다
                .andExpect(jsonPath("$.checkpoints[0].occurredAt").isEmpty())
                // 확인: 출고를 시작했으니 렌탈 시작 시각은 채워져 있어야 한다
                .andExpect(jsonPath("$.startedAt").isNotEmpty());
    }

    // [상황] 렌탈 상세를 본다.
    // [기대] 기기 일련번호가 들어 있다.
    @Test
    @DisplayName("렌탈 상세에 기기 시리얼(serial)이 있다")
    void getRentalHasSerial() throws Exception {
        // 준비: 시리얼 C02EXAMPLE01 기기로 렌탈을 만든다
        Rental rental = Rental.open(Fixtures.device("C02EXAMPLE01"), "R");
        // 준비: 가짜 서비스가 이 렌탈 ID로 조회하면 위 렌탈을 돌려주게 한다
        when(service.get(eq(rental.getPublicId()), any())).thenReturn(rental);
        // 실행: 관리자로 렌탈 상세를 조회한다
        mvc.perform(get("/api/v1/rentals/" + rental.getPublicId()).with(admin()))
                // 확인: 응답 코드가 200이어야 한다
                .andExpect(status().isOk())
                // 확인: 응답에 기기 시리얼이 있어야 한다
                .andExpect(jsonPath("$.serial").value("C02EXAMPLE01"));
    }

    // [상황] 렌탈 목록을 본다.
    // [기대] "지금 시점"은 가장 늦게 시작한 시점이고, 아무 시점도 시작 전이면 비어 있다.
    @Test
    @DisplayName("렌탈 목록 요약의 currentCheckpoint는 가장 늦게 시작한 시점이고, 시작 전이면 null")
    void listHasCurrentCheckpoint() throws Exception {
        // 준비: 출고를 시작할 렌탈(기기 S1)을 만든다
        Rental started = Rental.open(Fixtures.device("S1"), "R1");
        // 준비: 그 렌탈의 출고 시점을 시작한다
        Checkpoint out = started.startCheckpoint(CheckpointType.OUT);
        // 준비: 아무 시점도 시작하지 않은 렌탈(기기 S2)을 만든다
        Rental fresh = Rental.open(Fixtures.device("S2"), "R2");
        // 준비: 가짜 서비스가 기본 조건(상태·기기 없음(isNull), 문제만 보기 false, 커서 없음, 20개)으로 목록을 부르면
        when(service.list(isNull(), isNull(), eq(false), isNull(), eq(20), any()))
                // 두 렌탈이 든 한 쪽(다음 쪽 없음)을 돌려주게 한다
                .thenReturn(new RentalService.Page(List.of(started, fresh), null));
        // 실행: 관리자로 렌탈 목록을 조회한다
        mvc.perform(get("/api/v1/rentals").with(admin()))
                // 확인: 응답 코드가 200이어야 한다
                .andExpect(status().isOk())
                // 확인: 첫 렌탈의 지금 시점 ID가 출고 시점 ID여야 한다
                .andExpect(jsonPath("$.items[0].currentCheckpoint.checkpointId").value(out.getPublicId()))
                // 확인: 첫 렌탈의 지금 시점 종류가 출고여야 한다
                .andExpect(jsonPath("$.items[0].currentCheckpoint.type").value("OUT"))
                // 확인: 첫 렌탈의 지금 시점 상태가 열림이어야 한다
                .andExpect(jsonPath("$.items[0].currentCheckpoint.status").value("OPEN"))
                // 확인: 시점이 없는 두 번째 렌탈은 지금 시점이 비어 있어야 한다
                .andExpect(jsonPath("$.items[1].currentCheckpoint").isEmpty());
    }

    // [상황] 기기 하나로 걸러 렌탈 이력을 본다.
    // [기대] 그 기기 렌탈만 나오고 대여자·출고 시작·종료 시각이 있다.
    @Test
    @DisplayName("기기별 렌탈 이력: deviceId로 거르면 그 기기 렌탈만 받고, 항목에 대여자·출고 시작·종료 때가 있다")
    void listByDevice() throws Exception {
        // 준비: 기기 C02EXAMPLE01로 렌탈을 만든다
        Rental rental = Rental.open(Fixtures.device("C02EXAMPLE01"), "R-2026-0012");
        // 준비: 출고를 시작해 시작 시각을 채운다
        rental.startCheckpoint(CheckpointType.OUT);
        // 준비: 가짜 서비스가 이 기기 ID로 걸러 목록을 부르면
        when(service.list(isNull(), eq(DEVICE), eq(false), isNull(), eq(20), any()))
                // 위 렌탈 하나를 돌려주게 한다
                .thenReturn(new RentalService.Page(List.of(rental), null));
        // 실행: 관리자로 기기 ID 조건을 붙여 렌탈 목록을 조회한다
        mvc.perform(get("/api/v1/rentals?deviceId=" + DEVICE).with(admin()))
                // 확인: 응답 코드가 200이어야 한다
                .andExpect(status().isOk())
                // 확인: 항목에 대여자 참조가 있어야 한다
                .andExpect(jsonPath("$.items[0].renterRef").value("R-2026-0012"))
                // 확인: 출고 시작 시각이 채워져 있어야 한다
                .andExpect(jsonPath("$.items[0].startedAt").isNotEmpty())
                // 확인: 아직 종료 전이니 종료 시각은 비어 있어야 한다
                .andExpect(jsonPath("$.items[0].endedAt").isEmpty());
        // (도구) verify(가짜).메서드(조건) = 가짜의 그 메서드가 이 조건으로 불렸어야 한다는 검사
        // 확인: 서비스가 이 기기 ID 조건으로 불렸어야 한다 (주소의 deviceId가 제대로 넘어감)
        verify(service).list(isNull(), eq(DEVICE), eq(false), isNull(), eq(20), any());
    }

    // [상황] 기기 ID 모양이 틀렸다.
    // [기대] 400.
    //        ※ 응답 코드: 400 = 요청 내용이 형식에 맞지 않음
    @Test
    @DisplayName("기기별 렌탈 이력: deviceId 형식이 틀리면 400")
    void listByDeviceInvalidId() throws Exception {
        // 실행+확인: 기기 ID 모양이 틀린(bad) 목록 조회는 400이어야 한다
        mvc.perform(get("/api/v1/rentals?deviceId=bad").with(admin())).andExpect(status().isBadRequest());
        // 확인: 서비스는 불리지 않았어야 한다
        verifyNoInteractions(service);
    }

    // [상황] 고객이 남의 렌탈을 본다.
    // [기대] 403 OUT_OF_SCOPE.
    //        ※ 응답 코드: 403 = 권한 없음(누구인지는 알지만 허락되지 않은 일)
    @Test
    @DisplayName("CUSTOMER가 남의 렌탈을 보면 403 OUT_OF_SCOPE")
    void outOfScope() throws Exception {
        // 준비: 가짜 서비스가 이 렌탈 ID로 조회하면 "볼 수 없는 렌탈" 에러를 던지게 한다
        when(service.get(eq(DEVICE), any())).thenThrow(new DomainException(ErrorCode.OUT_OF_SCOPE, "남의 렌탈"));
        // 실행: 볼 수 있는 렌탈이 없는 고객 토큰으로 남의 렌탈을 조회한다
        mvc.perform(get("/api/v1/rentals/" + DEVICE).with(customer()))
                // 확인: 응답 코드가 403이어야 한다
                .andExpect(status().isForbidden())
                // 확인: 에러 코드가 OUT_OF_SCOPE(볼 수 있는 범위 밖)여야 한다
                .andExpect(jsonPath("$.code").value("OUT_OF_SCOPE"));
    }

    // [상황] 고객이 렌탈 목록을 본다.
    // [기대] 자기 렌탈(토큰의 렌탈 목록)만 나오고 일련번호·기종이 있다.
    @Test
    @DisplayName("렌탈 목록: CUSTOMER는 rentals 클레임으로 걸러지고 요약에 시리얼·기종 코드가 있다")
    void listRentals() throws Exception {
        // 준비: 기기 C02EXAMPLE01로 렌탈을 만든다
        Rental rental = Rental.open(Fixtures.device("C02EXAMPLE01"), "R");
        // 준비: 가짜 서비스가 상태 OPEN 조건으로 목록을 부르면
        when(service.list(eq(RentalStatus.OPEN), isNull(), eq(false), isNull(), eq(20), any()))
                // 위 렌탈 하나를 돌려주게 한다
                .thenReturn(new RentalService.Page(List.of(rental), null));
        // 실행: 이 렌탈을 볼 수 있는 고객 토큰으로 진행 중 렌탈 목록을 조회한다
        mvc.perform(get("/api/v1/rentals?status=OPEN").with(customer(rental.getPublicId())))
                // 확인: 응답 코드가 200이어야 한다
                .andExpect(status().isOk())
                // 확인: 요약에 기기 시리얼이 있어야 한다
                .andExpect(jsonPath("$.items[0].serial").value("C02EXAMPLE01"))
                // 확인: 요약에 기종 코드가 있어야 한다
                .andExpect(jsonPath("$.items[0].modelCode").value("MBA13-M2"))
                // 확인: 다음 쪽이 없으니 커서는 비어 있어야 한다
                .andExpect(jsonPath("$.nextCursor").isEmpty());
        // (도구) ArgumentCaptor = 가짜 메서드에 실제로 넘어간 값을 붙잡아 두는 도구
        // 준비: 서비스에 넘어간 로그인 사용자 정보를 붙잡을 그릇을 만든다
        ArgumentCaptor<CurrentUser> user = ArgumentCaptor.forClass(CurrentUser.class);
        // 확인: 서비스가 OPEN 조건으로 불렸어야 하고, 넘긴 사용자 정보를 붙잡는다
        verify(service).list(eq(RentalStatus.OPEN), isNull(), eq(false), isNull(), eq(20), user.capture());
        // (도구) assertThat(목록).containsExactly(값) = 목록에 정확히 이 값만 들어 있어야 한다는 검사
        // 확인: 사용자 정보의 볼 수 있는 렌탈 목록이 토큰에 넣은 렌탈 하나여야 한다 (서비스가 이걸로 거른다)
        org.assertj.core.api.Assertions.assertThat(user.getValue().rentals()).containsExactly(rental.getPublicId());
    }

    // [상황] 부스 토큰으로 렌탈 목록·상세·시점 시작을 부른다.
    // [기대] 목록만 볼 수 있고(태블릿에서 렌탈 고르기) 상세·시점 시작은 403.
    //        ※ 응답 코드: 403 = 권한 없음(누구인지는 알지만 허락되지 않은 일)
    @Test
    @DisplayName("부스 토큰은 렌탈 목록만 볼 수 있고, 렌탈 상세와 시점 시작은 403")
    void boothListsRentals() throws Exception {
        // 준비: 기기 C02EXAMPLE01로 렌탈을 만든다
        Rental rental = Rental.open(Fixtures.device("C02EXAMPLE01"), "R");
        // 준비: 가짜 서비스가 기본 조건으로 목록을 부르면
        when(service.list(isNull(), isNull(), eq(false), isNull(), eq(20), any()))
                // 위 렌탈 하나를 돌려주게 한다
                .thenReturn(new RentalService.Page(List.of(rental), null));
        // 실행: 부스 토큰으로 렌탈 목록을 조회한다
        mvc.perform(get("/api/v1/rentals").with(booth()))
                // 확인: 부스도 목록은 볼 수 있어 200이어야 한다 (태블릿에서 렌탈 고르기)
                .andExpect(status().isOk())
                // 확인: 목록에 기기 시리얼이 있어야 한다
                .andExpect(jsonPath("$.items[0].serial").value("C02EXAMPLE01"));
        // 준비: 서비스에 넘어간 로그인 사용자 정보를 붙잡을 그릇을 만든다
        ArgumentCaptor<CurrentUser> user = ArgumentCaptor.forClass(CurrentUser.class);
        // 확인: 서비스가 불렸어야 하고, 넘긴 사용자 정보를 붙잡는다
        verify(service).list(isNull(), isNull(), eq(false), isNull(), eq(20), user.capture());
        // 확인: 넘어간 사용자가 부스로 인식돼야 한다 (서비스가 부스용 거르기를 하도록)
        org.assertj.core.api.Assertions.assertThat(user.getValue().isBooth()).isTrue();

        // 실행+확인: 부스가 렌탈 상세를 보면 403이어야 한다
        mvc.perform(get("/api/v1/rentals/" + DEVICE).with(booth())).andExpect(status().isForbidden());
        // 실행: 부스가 출고 시점 시작을 요청한다
        mvc.perform(post("/api/v1/rentals/" + DEVICE + "/checkpoints").with(booth())
                .contentType(MediaType.APPLICATION_JSON).content("{\"type\":\"OUT\"}"))
                // 확인: 시점 시작은 관리자만이니 403이어야 한다
                .andExpect(status().isForbidden());
    }

    // [상황] 관리자가 시점을 시작하고, 순서를 어겨 시작하고, 고객이 시작해 본다.
    // [기대] 관리자만 가능, 순서 위반은 409 CHECKPOINT_ORDER_VIOLATION.
    //        ※ 응답 코드: 409 = 지금 상태와 맞지 않아 거절(이미 있음·순서 어긋남 등)
    @Test
    @DisplayName("시점 시작은 ADMIN만, 순서를 어기면 409 CHECKPOINT_ORDER_VIOLATION")
    void startCheckpoint() throws Exception {
        // 준비: 렌탈 ID로 쓸 21자 ID를 정한다
        String rentalId = DEVICE;
        // 준비: 가짜 서비스가 이 렌탈에 수령(RCV) 시점을 시작하면
        when(service.startCheckpoint(rentalId, CheckpointType.RCV))
                // "출고가 먼저" 순서 위반 에러를 던지게 한다
                .thenThrow(new DomainException(ErrorCode.CHECKPOINT_ORDER_VIOLATION, "출고가 먼저"));
        // 실행: 관리자로 수령 시점 시작을 요청한다
        mvc.perform(post("/api/v1/rentals/" + rentalId + "/checkpoints").with(admin())
                .contentType(MediaType.APPLICATION_JSON).content("{\"type\":\"RCV\"}"))
                // 확인: 응답 코드가 409여야 한다
                .andExpect(status().isConflict())
                // 확인: 에러 코드가 CHECKPOINT_ORDER_VIOLATION이어야 한다
                .andExpect(jsonPath("$.code").value("CHECKPOINT_ORDER_VIOLATION"));
        // 실행: 이 렌탈의 고객 토큰으로 시점 시작을 요청한다
        mvc.perform(post("/api/v1/rentals/" + rentalId + "/checkpoints").with(customer(rentalId))
                .contentType(MediaType.APPLICATION_JSON).content("{\"type\":\"RCV\"}"))
                // 확인: 고객은 시점을 시작할 수 없어 403이어야 한다
                .andExpect(status().isForbidden());

        // 준비: 응답으로 쓸 렌탈을 만든다
        Rental rental = Rental.open(Fixtures.device("S1"), "R");
        // 준비: 그 렌탈의 출고 시점을 만든다
        Checkpoint out = rental.startCheckpoint(CheckpointType.OUT);
        // 준비: 가짜 서비스가 출고 시점을 시작하면 위 출고 시점을 돌려주게 한다
        when(service.startCheckpoint(rentalId, CheckpointType.OUT)).thenReturn(out);
        // 실행: 관리자로 출고 시점 시작을 요청한다
        mvc.perform(post("/api/v1/rentals/" + rentalId + "/checkpoints").with(admin())
                .contentType(MediaType.APPLICATION_JSON).content("{\"type\":\"OUT\"}"))
                // 확인: 응답 코드가 201이어야 한다
                .andExpect(status().isCreated())
                // 확인: 만든 시점 종류가 출고여야 한다
                .andExpect(jsonPath("$.type").value("OUT"))
                // 확인: 만든 시점 상태가 열림이어야 한다
                .andExpect(jsonPath("$.status").value("OPEN"));
    }

    // [상황] 반납 완료(RETURNED) 렌탈, 그렇지 않은 렌탈, 없는 렌탈을 종료한다.
    // [기대] 204, 409 RENTAL_NOT_CLOSABLE, 404.
    //        ※ 응답 코드: 204 = 처리됨, 돌려줄 내용 없음, 404 = 찾을 수 없음(없는 주소나 데이터)
    @Test
    @DisplayName("종료: RETURNED면 204, 아니면 409 RENTAL_NOT_CLOSABLE, 없는 렌탈은 404")
    void closeRental() throws Exception {
        // 실행+확인: 관리자가 렌탈을 종료하면 204여야 한다 (가짜 서비스는 따로 정하지 않아 그냥 성공)
        mvc.perform(post("/api/v1/rentals/" + DEVICE + "/close").with(admin())).andExpect(status().isNoContent());
        // (도구) doThrow(예외).when(가짜).메서드(값) = 그 메서드가 불리면 이 예외를 던지게 정해 두는 것 (돌려줄 값 없는 메서드용)
        // 준비: 반납 전 렌탈을 종료하면 "종료할 수 없음" 에러를 던지게 한다
        org.mockito.Mockito.doThrow(new DomainException(ErrorCode.RENTAL_NOT_CLOSABLE, "반납 전"))
                .when(service).close("notReturned0000000001");
        // 실행: 관리자로 반납 전 렌탈을 종료한다
        mvc.perform(post("/api/v1/rentals/notReturned0000000001/close").with(admin()))
                // 확인: 응답 코드가 409여야 한다
                .andExpect(status().isConflict())
                // 확인: 에러 코드가 RENTAL_NOT_CLOSABLE이어야 한다
                .andExpect(jsonPath("$.code").value("RENTAL_NOT_CLOSABLE"));
        // 준비: 없는 렌탈을 종료하면 "렌탈 없음" 에러를 던지게 한다
        org.mockito.Mockito.doThrow(DomainException.notFound("렌탈")).when(service).close("missing00000000000001");
        // 실행: 관리자로 없는 렌탈을 종료한다
        mvc.perform(post("/api/v1/rentals/missing00000000000001/close").with(admin()))
                // 확인: 응답 코드가 404여야 한다
                .andExpect(status().isNotFound());
        // 실행+확인: 렌탈 ID 모양이 틀리면(short) 400이어야 한다
        mvc.perform(post("/api/v1/rentals/short/close").with(admin())).andExpect(status().isBadRequest());
    }

    // [상황] 관리자가 시험 렌탈과 실제 렌탈을 지운다.
    // [기대] 시험 렌탈 204, 실제 렌탈 409 NOT_TEST_DATA.
    //        ※ 응답 코드: 204 = 처리됨, 돌려줄 내용 없음, 409 = 지금 상태와 맞지 않아 거절(이미 있음·순서 어긋남 등)
    @Test
    @DisplayName("관리자가 시험 렌탈을 지우면 204, 시험 데이터가 아니면 409 NOT_TEST_DATA")
    void deleteTestRental() throws Exception {
        // 실행+확인: 관리자가 렌탈을 지우면 204여야 한다
        mvc.perform(delete("/api/v1/rentals/" + DEVICE).with(admin())).andExpect(status().isNoContent());
        // 확인: 서비스의 시험 렌탈 지우기가 이 ID로 불렸어야 한다
        verify(service).deleteTestRental(DEVICE);

        // 준비: 다른 렌탈 ID를 지우면 "시험 데이터 아님" 에러를 던지게 한다
        doThrow(new DomainException(ErrorCode.NOT_TEST_DATA, "시험 아님")).when(service).deleteTestRental("Xs0HLv6QRbDILPWOcz5I2");
        // 실행: 관리자로 실제 렌탈을 지운다
        mvc.perform(delete("/api/v1/rentals/Xs0HLv6QRbDILPWOcz5I2").with(admin()))
                // 확인: 409이고 에러 코드가 NOT_TEST_DATA여야 한다
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("NOT_TEST_DATA"));
    }

    // [상황] 고객·부스가 렌탈을 지운다.
    // [기대] 403.
    //        ※ 응답 코드: 403 = 권한 없음(누구인지는 알지만 허락되지 않은 일)
    @Test
    @DisplayName("고객·부스는 렌탈을 지울 수 없다(403)")
    void deleteNeedsAdmin() throws Exception {
        // 실행+확인: 고객이 렌탈을 지우면 403이어야 한다
        mvc.perform(delete("/api/v1/rentals/" + DEVICE).with(customer())).andExpect(status().isForbidden());
        // 실행+확인: 부스가 렌탈을 지워도 403이어야 한다
        mvc.perform(delete("/api/v1/rentals/" + DEVICE).with(booth())).andExpect(status().isForbidden());
    }

    // [상황] 고객 이름·반납 예정일·메모를 넣어 렌탈을 만든다. 64자 넘는 이름도 넣어 본다.
    // [기대] 응답에 그대로 실리고, 너무 긴 이름은 400.
    //        ※ 응답 코드: 400 = 요청 내용이 형식에 맞지 않음
    @Test
    @DisplayName("렌탈 생성 때 고객 이름·반납 예정일·메모를 받아 응답에 싣고, 64자를 넘는 이름은 400")
    void openWithDetails() throws Exception {
        // 준비: 응답으로 돌려줄 렌탈을 만든다
        Rental rental = Rental.open(Fixtures.device("S1"), "R-1");
        // 준비: 그 렌탈에 고객 이름 김고객·반납 예정일 11/19·메모 충전기를 넣는다
        rental.updateDetails(RentalDetails.of("김고객", java.time.LocalDate.parse("2026-11-19"), "충전기"));
        // (도구) ArgumentCaptor = 가짜 메서드에 실제로 넘어간 값을 붙잡아 두는 도구
        // 준비: 서비스에 넘어간 고객 정보(이름·예정일·메모)를 붙잡을 그릇을 만든다
        ArgumentCaptor<RentalDetails> details = ArgumentCaptor.forClass(RentalDetails.class);
        // 준비: 가짜 서비스가 렌탈을 열면 넘어간 고객 정보를 붙잡고 위 렌탈을 돌려주게 한다
        when(service.open(eq(DEVICE), eq("R-1"), details.capture())).thenReturn(rental);
        // 실행: 관리자로 고객 이름(앞뒤 공백 포함)·반납 예정일·메모를 담아 렌탈 생성 요청을 보낸다
        mvc.perform(post("/api/v1/rentals").with(admin()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"deviceId\":\"" + DEVICE + "\",\"renterRef\":\"R-1\",\"customerName\":\" 김고객 \",\"dueAt\":\"2026-11-19\",\"note\":\"충전기\"}"))
                // 확인: 응답 코드가 201이어야 한다
                .andExpect(status().isCreated())
                // 확인: 응답의 고객 이름이 김고객이어야 한다
                .andExpect(jsonPath("$.customerName").value("김고객"))
                // 확인: 응답의 반납 예정일이 11/19여야 한다
                .andExpect(jsonPath("$.dueAt").value("2026-11-19"))
                // 확인: 관리자에게는 메모가 보여야 한다
                .andExpect(jsonPath("$.note").value("충전기"));
        // (도구) assertThat(실제값).isEqualTo(기대값) = "실제값이 기대값과 같아야 한다"는 검사
        // 확인: 서비스에 넘어간 이름은 앞뒤 공백이 지워진 김고객이어야 한다
        org.assertj.core.api.Assertions.assertThat(details.getValue().customerName().value()).isEqualTo("김고객");
        // 실행: 고객 이름을 65자(한도 64자 초과)로 넣어 렌탈 생성 요청을 보낸다
        mvc.perform(post("/api/v1/rentals").with(admin()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"deviceId\":\"" + DEVICE + "\",\"renterRef\":\"R-1\",\"customerName\":\"" + "가".repeat(65) + "\"}"))
                // 확인: 너무 길어 400이어야 한다
                .andExpect(status().isBadRequest());
    }

    // [상황] 고객이 자기 렌탈을 본다.
    // [기대] 관리자 메모는 비어 보이고 이름·반납 예정일은 보인다.
    @Test
    @DisplayName("고객이 자기 렌탈을 보면 관리자 메모는 null이고 이름·반납 예정일은 보인다")
    void customerSeesNoNote() throws Exception {
        // 준비: 렌탈을 만든다
        Rental rental = Rental.open(Fixtures.device("S1"), "R");
        // 준비: 고객 이름·반납 예정일·관리자 메모("내부 메모")를 넣는다
        rental.updateDetails(RentalDetails.of("김고객", java.time.LocalDate.parse("2026-11-19"), "내부 메모"));
        // 준비: 가짜 서비스가 이 렌탈 ID로 조회하면 위 렌탈을 돌려주게 한다
        when(service.get(eq(rental.getPublicId()), any())).thenReturn(rental);
        // 실행: 이 렌탈의 고객 토큰으로 상세를 조회한다
        mvc.perform(get("/api/v1/rentals/" + rental.getPublicId()).with(customer(rental.getPublicId())))
                // 확인: 고객 이름은 보여야 한다
                .andExpect(jsonPath("$.customerName").value("김고객"))
                // 확인: 반납 예정일도 보여야 한다
                .andExpect(jsonPath("$.dueAt").value("2026-11-19"))
                // 확인: 관리자 메모는 고객에게 비어 보여야 한다 (내부 메모 노출 방지)
                .andExpect(jsonPath("$.note").isEmpty());
        // 실행: 관리자로 같은 렌탈을 조회한다
        mvc.perform(get("/api/v1/rentals/" + rental.getPublicId()).with(admin()))
                // 확인: 관리자에게는 메모가 보여야 한다
                .andExpect(jsonPath("$.note").value("내부 메모"));
    }

    // [상황] "문제 있는 검사만" 목록을 관리자·부스가 본다.
    // [기대] 거르기 조건을 넘기고 전체 건수를 싣는다. 고객 이름은 관리자에게만, 부스에는 비어 보인다.
    @Test
    @DisplayName("목록: problem=true를 서비스에 넘기고 totalCount를 싣는다. 고객 이름은 관리자에게만, 부스에는 null")
    void listProblemAndTotal() throws Exception {
        // 준비: 렌탈을 만든다
        Rental rental = Rental.open(Fixtures.device("S1"), "R");
        // 준비: 고객 이름만 김고객으로 넣는다
        rental.updateDetails(RentalDetails.of("김고객", null, null));
        // 준비: 출고 시점을 시작하고 등급 C(문제 있음)를 기록한다
        rental.startCheckpoint(com.gakdo.trace.rental.rental.domain.CheckpointType.OUT).recordGrade("C");
        // 준비: 가짜 서비스가 "문제만 보기(true)" 조건으로 목록을 부르면
        when(service.list(isNull(), isNull(), eq(true), isNull(), eq(20), any()))
                // 위 렌탈 하나, 다음 쪽 커서 next, 전체 건수 42를 돌려주게 한다
                .thenReturn(new RentalService.Page(List.of(rental), "next", 42));
        // 실행: 관리자로 problem=true 조건을 붙여 목록을 조회한다
        mvc.perform(get("/api/v1/rentals?problem=true").with(admin()))
                // 확인: 응답 코드가 200이어야 한다
                .andExpect(status().isOk())
                // 확인: 전체 건수 42가 실려야 한다
                .andExpect(jsonPath("$.totalCount").value(42))
                // 확인: 관리자에게는 고객 이름이 보여야 한다
                .andExpect(jsonPath("$.items[0].customerName").value("김고객"))
                // 확인: 지금 시점의 등급 C가 보여야 한다
                .andExpect(jsonPath("$.items[0].currentCheckpoint.grade").value("C"));
        // 실행: 부스 토큰으로 같은 목록을 조회한다
        mvc.perform(get("/api/v1/rentals?problem=true").with(booth()))
                // 확인: 부스에는 고객 이름이 비어 보여야 한다 (개인정보 보호)
                .andExpect(jsonPath("$.items[0].customerName").isEmpty());
    }

    // [상황] 렌탈 정보를 일부 고친다. 빈 본문·모르는 칸·틀린 날짜·고객 요청도 해 본다.
    // [기대] 보낸 칸만 바뀌고 null은 비운다. 잘못된 요청은 400, 고객은 403.
    //        ※ 응답 코드: 400 = 요청 내용이 형식에 맞지 않음, 403 = 권한 없음(누구인지는 알지만 허락되지 않은 일)
    @Test
    @DisplayName("PATCH: 보낸 칸만 바꾸고 null은 비우며, 빈 본문·모르는 필드·잘못된 날짜는 400, 고객은 403")
    void patchDetails() throws Exception {
        // 준비: 렌탈을 만든다
        Rental rental = Rental.open(Fixtures.device("S1"), "R");
        // 준비: 서비스에 넘어간 변경 내용을 붙잡을 그릇을 만든다
        ArgumentCaptor<RentalDetails> details = ArgumentCaptor.forClass(RentalDetails.class);
        // 준비: 가짜 서비스가 이 렌탈 정보를 고치면 넘어간 변경 내용을 붙잡고 위 렌탈을 돌려주게 한다
        when(service.updateDetails(eq(rental.getPublicId()), details.capture())).thenReturn(rental);
        // 준비: 고칠 렌탈의 주소를 만든다
        String url = "/api/v1/rentals/" + rental.getPublicId();
        // 실행: 관리자로 반납 예정일은 11/20, 메모는 null(비우기)로 PATCH 요청을 보낸다 (이름은 안 보냄)
        mvc.perform(patch(url).with(admin()).contentType(MediaType.APPLICATION_JSON).content("{\"dueAt\":\"2026-11-20\",\"note\":null}"))
                // 확인: 응답 코드가 200이어야 한다
                .andExpect(status().isOk());
        // 준비: 서비스에 실제로 넘어간 변경 내용을 꺼낸다
        RentalDetails sent = details.getValue();
        // 확인: 안 보낸 이름은 "그대로 둠"이라는 뜻으로 null이어야 한다
        org.assertj.core.api.Assertions.assertThat(sent.customerName()).isNull();
        // 확인: 반납 예정일은 11/20이어야 한다
        org.assertj.core.api.Assertions.assertThat(sent.dueAt().value()).isEqualTo(java.time.LocalDate.parse("2026-11-20"));
        // 확인: 메모 칸은 "보냈음"으로 표시돼 있어야 한다 (null이 아니어야 함)
        org.assertj.core.api.Assertions.assertThat(sent.note()).isNotNull();
        // 확인: 그 안의 값은 null = "비우기"여야 한다 (안 보냄과 null 보냄을 구분)
        org.assertj.core.api.Assertions.assertThat(sent.note().value()).isNull();
        // 실행: 빈 본문으로 PATCH 요청을 보낸다
        mvc.perform(patch(url).with(admin()).contentType(MediaType.APPLICATION_JSON).content("{}"))
                // 확인: 바꿀 것이 없으니 400이어야 한다
                .andExpect(status().isBadRequest());
        // 실행: 고칠 수 없는 칸 status를 넣어 보낸다
        mvc.perform(patch(url).with(admin()).contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"CLOSED\"}"))
                // 확인: 모르는 칸이라 400이어야 한다
                .andExpect(status().isBadRequest());
        // 실행: 날짜를 틀린 형식(11/20)으로 보낸다
        mvc.perform(patch(url).with(admin()).contentType(MediaType.APPLICATION_JSON).content("{\"dueAt\":\"11/20\"}"))
                // 확인: 날짜 형식(YYYY-MM-DD)이 아니라 400이어야 한다
                .andExpect(status().isBadRequest());
        // 실행: 이 렌탈의 고객 토큰으로 메모를 고치려 한다
        mvc.perform(patch(url).with(customer(rental.getPublicId())).contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"x\"}"))
                // 확인: 고객은 고칠 수 없어 403이어야 한다
                .andExpect(status().isForbidden());
    }
}
