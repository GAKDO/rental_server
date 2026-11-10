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

@WebMvcTest(controllers = RentalController.class)
class RentalApiTest extends WebTestBase {

    private static final String DEVICE = "IPv6mcQOlTkKj6mOecAQt";

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private RentalService service;

    @Test
    @DisplayName("렌탈 생성은 201, Location, 상태 OPEN, checkpoints []")
    void openRental() throws Exception {
        Rental rental = Rental.open(Fixtures.device("S1"), "R-2026-0012");
        when(service.open(eq(DEVICE), eq("R-2026-0012"), any())).thenReturn(rental);
        mvc.perform(post("/api/v1/rentals").with(admin()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"deviceId\":\"" + DEVICE + "\",\"renterRef\":\"R-2026-0012\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/rentals/" + rental.getPublicId()))
                .andExpect(jsonPath("$.rentalId").value(rental.getPublicId()))
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.startedAt").isEmpty())
                .andExpect(jsonPath("$.checkpoints").isEmpty());
    }

    @Test
    @DisplayName("같은 시리얼로 진행 중인 렌탈이 있으면 409 RENTAL_OPEN_EXISTS, 수리 중·폐기 기기면 409 DEVICE_NOT_ACTIVE")
    void openConflicts() throws Exception {
        when(service.open(any(), any(), any()))
                .thenThrow(new DomainException(ErrorCode.RENTAL_OPEN_EXISTS, "있음"))
                .thenThrow(new DomainException(ErrorCode.DEVICE_NOT_ACTIVE, "수리 중"));
        String body = "{\"deviceId\":\"" + DEVICE + "\",\"renterRef\":\"R\"}";
        mvc.perform(post("/api/v1/rentals").with(admin()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("RENTAL_OPEN_EXISTS"));
        mvc.perform(post("/api/v1/rentals").with(admin()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DEVICE_NOT_ACTIVE"));
    }

    @Test
    @DisplayName("deviceId가 PublicId 형식이 아니거나 renterRef가 없으면 400")
    void openValidation() throws Exception {
        mvc.perform(post("/api/v1/rentals").with(admin()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"deviceId\":\"0193a1c2-0000-7000-8000-000000000501\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.length()").value(2));
        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("렌탈 조회는 세 시점 상태를 OUT·RCV·RET 순으로 준다")
    void getRental() throws Exception {
        Rental rental = Rental.open(Fixtures.device("S1"), "R");
        Checkpoint out = rental.startCheckpoint(CheckpointType.OUT);
        when(service.get(eq(rental.getPublicId()), any())).thenReturn(rental);
        mvc.perform(get("/api/v1/rentals/" + rental.getPublicId()).with(customer(rental.getPublicId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.checkpoints[0].checkpointId").value(out.getPublicId()))
                .andExpect(jsonPath("$.checkpoints[0].type").value("OUT"))
                .andExpect(jsonPath("$.checkpoints[0].status").value("OPEN"))
                .andExpect(jsonPath("$.checkpoints[0].occurredAt").isEmpty())
                .andExpect(jsonPath("$.startedAt").isNotEmpty());
    }

    @Test
    @DisplayName("렌탈 상세에 기기 시리얼(serial)이 있다")
    void getRentalHasSerial() throws Exception {
        Rental rental = Rental.open(Fixtures.device("C02EXAMPLE01"), "R");
        when(service.get(eq(rental.getPublicId()), any())).thenReturn(rental);
        mvc.perform(get("/api/v1/rentals/" + rental.getPublicId()).with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.serial").value("C02EXAMPLE01"));
    }

    @Test
    @DisplayName("렌탈 목록 요약의 currentCheckpoint는 가장 늦게 시작한 시점이고, 시작 전이면 null")
    void listHasCurrentCheckpoint() throws Exception {
        Rental started = Rental.open(Fixtures.device("S1"), "R1");
        Checkpoint out = started.startCheckpoint(CheckpointType.OUT);
        Rental fresh = Rental.open(Fixtures.device("S2"), "R2");
        when(service.list(isNull(), isNull(), eq(false), isNull(), eq(20), any()))
                .thenReturn(new RentalService.Page(List.of(started, fresh), null));
        mvc.perform(get("/api/v1/rentals").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].currentCheckpoint.checkpointId").value(out.getPublicId()))
                .andExpect(jsonPath("$.items[0].currentCheckpoint.type").value("OUT"))
                .andExpect(jsonPath("$.items[0].currentCheckpoint.status").value("OPEN"))
                .andExpect(jsonPath("$.items[1].currentCheckpoint").isEmpty());
    }

    @Test
    @DisplayName("기기별 렌탈 이력: deviceId로 거르면 그 기기 렌탈만 받고, 항목에 대여자·출고 시작·종료 때가 있다")
    void listByDevice() throws Exception {
        Rental rental = Rental.open(Fixtures.device("C02EXAMPLE01"), "R-2026-0012");
        rental.startCheckpoint(CheckpointType.OUT);
        when(service.list(isNull(), eq(DEVICE), eq(false), isNull(), eq(20), any()))
                .thenReturn(new RentalService.Page(List.of(rental), null));
        mvc.perform(get("/api/v1/rentals?deviceId=" + DEVICE).with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].renterRef").value("R-2026-0012"))
                .andExpect(jsonPath("$.items[0].startedAt").isNotEmpty())
                .andExpect(jsonPath("$.items[0].endedAt").isEmpty());
        verify(service).list(isNull(), eq(DEVICE), eq(false), isNull(), eq(20), any());
    }

    @Test
    @DisplayName("기기별 렌탈 이력: deviceId 형식이 틀리면 400")
    void listByDeviceInvalidId() throws Exception {
        mvc.perform(get("/api/v1/rentals?deviceId=bad").with(admin())).andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("CUSTOMER가 남의 렌탈을 보면 403 OUT_OF_SCOPE")
    void outOfScope() throws Exception {
        when(service.get(eq(DEVICE), any())).thenThrow(new DomainException(ErrorCode.OUT_OF_SCOPE, "남의 렌탈"));
        mvc.perform(get("/api/v1/rentals/" + DEVICE).with(customer()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("OUT_OF_SCOPE"));
    }

    @Test
    @DisplayName("렌탈 목록: CUSTOMER는 rentals 클레임으로 걸러지고 요약에 시리얼·기종 코드가 있다")
    void listRentals() throws Exception {
        Rental rental = Rental.open(Fixtures.device("C02EXAMPLE01"), "R");
        when(service.list(eq(RentalStatus.OPEN), isNull(), eq(false), isNull(), eq(20), any()))
                .thenReturn(new RentalService.Page(List.of(rental), null));
        mvc.perform(get("/api/v1/rentals?status=OPEN").with(customer(rental.getPublicId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].serial").value("C02EXAMPLE01"))
                .andExpect(jsonPath("$.items[0].modelCode").value("MBA13-M2"))
                .andExpect(jsonPath("$.nextCursor").isEmpty());
        ArgumentCaptor<CurrentUser> user = ArgumentCaptor.forClass(CurrentUser.class);
        verify(service).list(eq(RentalStatus.OPEN), isNull(), eq(false), isNull(), eq(20), user.capture());
        org.assertj.core.api.Assertions.assertThat(user.getValue().rentals()).containsExactly(rental.getPublicId());
    }

    @Test
    @DisplayName("부스 토큰은 렌탈 목록만 볼 수 있고, 렌탈 상세와 시점 시작은 403")
    void boothListsRentals() throws Exception {
        Rental rental = Rental.open(Fixtures.device("C02EXAMPLE01"), "R");
        when(service.list(isNull(), isNull(), eq(false), isNull(), eq(20), any()))
                .thenReturn(new RentalService.Page(List.of(rental), null));
        mvc.perform(get("/api/v1/rentals").with(booth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].serial").value("C02EXAMPLE01"));
        ArgumentCaptor<CurrentUser> user = ArgumentCaptor.forClass(CurrentUser.class);
        verify(service).list(isNull(), isNull(), eq(false), isNull(), eq(20), user.capture());
        org.assertj.core.api.Assertions.assertThat(user.getValue().isBooth()).isTrue();

        mvc.perform(get("/api/v1/rentals/" + DEVICE).with(booth())).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/rentals/" + DEVICE + "/checkpoints").with(booth())
                .contentType(MediaType.APPLICATION_JSON).content("{\"type\":\"OUT\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("시점 시작은 ADMIN만, 순서를 어기면 409 CHECKPOINT_ORDER_VIOLATION")
    void startCheckpoint() throws Exception {
        String rentalId = DEVICE;
        when(service.startCheckpoint(rentalId, CheckpointType.RCV))
                .thenThrow(new DomainException(ErrorCode.CHECKPOINT_ORDER_VIOLATION, "출고가 먼저"));
        mvc.perform(post("/api/v1/rentals/" + rentalId + "/checkpoints").with(admin())
                .contentType(MediaType.APPLICATION_JSON).content("{\"type\":\"RCV\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CHECKPOINT_ORDER_VIOLATION"));
        mvc.perform(post("/api/v1/rentals/" + rentalId + "/checkpoints").with(customer(rentalId))
                .contentType(MediaType.APPLICATION_JSON).content("{\"type\":\"RCV\"}"))
                .andExpect(status().isForbidden());

        Rental rental = Rental.open(Fixtures.device("S1"), "R");
        Checkpoint out = rental.startCheckpoint(CheckpointType.OUT);
        when(service.startCheckpoint(rentalId, CheckpointType.OUT)).thenReturn(out);
        mvc.perform(post("/api/v1/rentals/" + rentalId + "/checkpoints").with(admin())
                .contentType(MediaType.APPLICATION_JSON).content("{\"type\":\"OUT\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.type").value("OUT"))
                .andExpect(jsonPath("$.status").value("OPEN"));
    }

    @Test
    @DisplayName("종료: RETURNED면 204, 아니면 409 RENTAL_NOT_CLOSABLE, 없는 렌탈은 404")
    void closeRental() throws Exception {
        mvc.perform(post("/api/v1/rentals/" + DEVICE + "/close").with(admin())).andExpect(status().isNoContent());
        org.mockito.Mockito.doThrow(new DomainException(ErrorCode.RENTAL_NOT_CLOSABLE, "반납 전"))
                .when(service).close("notReturned0000000001");
        mvc.perform(post("/api/v1/rentals/notReturned0000000001/close").with(admin()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RENTAL_NOT_CLOSABLE"));
        org.mockito.Mockito.doThrow(DomainException.notFound("렌탈")).when(service).close("missing00000000000001");
        mvc.perform(post("/api/v1/rentals/missing00000000000001/close").with(admin()))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/rentals/short/close").with(admin())).andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("관리자가 시험 렌탈을 지우면 204, 시험 데이터가 아니면 409 NOT_TEST_DATA")
    void deleteTestRental() throws Exception {
        mvc.perform(delete("/api/v1/rentals/" + DEVICE).with(admin())).andExpect(status().isNoContent());
        verify(service).deleteTestRental(DEVICE);

        doThrow(new DomainException(ErrorCode.NOT_TEST_DATA, "시험 아님")).when(service).deleteTestRental("Xs0HLv6QRbDILPWOcz5I2");
        mvc.perform(delete("/api/v1/rentals/Xs0HLv6QRbDILPWOcz5I2").with(admin()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("NOT_TEST_DATA"));
    }

    @Test
    @DisplayName("고객·부스는 렌탈을 지울 수 없다(403)")
    void deleteNeedsAdmin() throws Exception {
        mvc.perform(delete("/api/v1/rentals/" + DEVICE).with(customer())).andExpect(status().isForbidden());
        mvc.perform(delete("/api/v1/rentals/" + DEVICE).with(booth())).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("렌탈 생성 때 고객 이름·반납 예정일·메모를 받아 응답에 싣고, 64자를 넘는 이름은 400")
    void openWithDetails() throws Exception {
        Rental rental = Rental.open(Fixtures.device("S1"), "R-1");
        rental.updateDetails(RentalDetails.of("김고객", java.time.LocalDate.parse("2026-11-19"), "충전기"));
        ArgumentCaptor<RentalDetails> details = ArgumentCaptor.forClass(RentalDetails.class);
        when(service.open(eq(DEVICE), eq("R-1"), details.capture())).thenReturn(rental);
        mvc.perform(post("/api/v1/rentals").with(admin()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"deviceId\":\"" + DEVICE + "\",\"renterRef\":\"R-1\",\"customerName\":\" 김고객 \",\"dueAt\":\"2026-11-19\",\"note\":\"충전기\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.customerName").value("김고객"))
                .andExpect(jsonPath("$.dueAt").value("2026-11-19"))
                .andExpect(jsonPath("$.note").value("충전기"));
        org.assertj.core.api.Assertions.assertThat(details.getValue().customerName().value()).isEqualTo("김고객");
        mvc.perform(post("/api/v1/rentals").with(admin()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"deviceId\":\"" + DEVICE + "\",\"renterRef\":\"R-1\",\"customerName\":\"" + "가".repeat(65) + "\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("고객이 자기 렌탈을 보면 관리자 메모는 null이고 이름·반납 예정일은 보인다")
    void customerSeesNoNote() throws Exception {
        Rental rental = Rental.open(Fixtures.device("S1"), "R");
        rental.updateDetails(RentalDetails.of("김고객", java.time.LocalDate.parse("2026-11-19"), "내부 메모"));
        when(service.get(eq(rental.getPublicId()), any())).thenReturn(rental);
        mvc.perform(get("/api/v1/rentals/" + rental.getPublicId()).with(customer(rental.getPublicId())))
                .andExpect(jsonPath("$.customerName").value("김고객"))
                .andExpect(jsonPath("$.dueAt").value("2026-11-19"))
                .andExpect(jsonPath("$.note").isEmpty());
        mvc.perform(get("/api/v1/rentals/" + rental.getPublicId()).with(admin()))
                .andExpect(jsonPath("$.note").value("내부 메모"));
    }

    @Test
    @DisplayName("목록: problem=true를 서비스에 넘기고 totalCount를 싣는다. 고객 이름은 관리자에게만, 부스에는 null")
    void listProblemAndTotal() throws Exception {
        Rental rental = Rental.open(Fixtures.device("S1"), "R");
        rental.updateDetails(RentalDetails.of("김고객", null, null));
        when(service.list(isNull(), isNull(), eq(true), isNull(), eq(20), any()))
                .thenReturn(new RentalService.Page(List.of(rental), "next", 42));
        mvc.perform(get("/api/v1/rentals?problem=true").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCount").value(42))
                .andExpect(jsonPath("$.items[0].customerName").value("김고객"));
        mvc.perform(get("/api/v1/rentals?problem=true").with(booth()))
                .andExpect(jsonPath("$.items[0].customerName").isEmpty());
    }

    @Test
    @DisplayName("PATCH: 보낸 칸만 바꾸고 null은 비우며, 빈 본문·모르는 필드·잘못된 날짜는 400, 고객은 403")
    void patchDetails() throws Exception {
        Rental rental = Rental.open(Fixtures.device("S1"), "R");
        ArgumentCaptor<RentalDetails> details = ArgumentCaptor.forClass(RentalDetails.class);
        when(service.updateDetails(eq(rental.getPublicId()), details.capture())).thenReturn(rental);
        String url = "/api/v1/rentals/" + rental.getPublicId();
        mvc.perform(patch(url).with(admin()).contentType(MediaType.APPLICATION_JSON).content("{\"dueAt\":\"2026-11-20\",\"note\":null}"))
                .andExpect(status().isOk());
        RentalDetails sent = details.getValue();
        org.assertj.core.api.Assertions.assertThat(sent.customerName()).isNull();
        org.assertj.core.api.Assertions.assertThat(sent.dueAt().value()).isEqualTo(java.time.LocalDate.parse("2026-11-20"));
        org.assertj.core.api.Assertions.assertThat(sent.note()).isNotNull();
        org.assertj.core.api.Assertions.assertThat(sent.note().value()).isNull();
        mvc.perform(patch(url).with(admin()).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        mvc.perform(patch(url).with(admin()).contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"CLOSED\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(patch(url).with(admin()).contentType(MediaType.APPLICATION_JSON).content("{\"dueAt\":\"11/20\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(patch(url).with(customer(rental.getPublicId())).contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"x\"}"))
                .andExpect(status().isForbidden());
    }
}
