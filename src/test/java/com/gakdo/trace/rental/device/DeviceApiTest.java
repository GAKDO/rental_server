// 이 파일이 확인하는 것: 관리자 화면의 기기·기종 API(등록·조회·수정·삭제·검색)와 누가 쓸 수 있는지.
// DB 대신 가짜 서비스를 쓰고, 보안 규칙과 오류 형식은 운영과 같다.

package com.gakdo.trace.rental.device;

import static com.gakdo.trace.rental.support.TestJwts.admin;
import static com.gakdo.trace.rental.support.TestJwts.customer;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.gakdo.trace.rental.common.domain.DomainException;
import com.gakdo.trace.rental.common.domain.ErrorCode;
import com.gakdo.trace.rental.device.api.DeviceController;
import com.gakdo.trace.rental.device.application.DeviceService;
import com.gakdo.trace.rental.device.domain.Device;
import com.gakdo.trace.rental.device.domain.DeviceOs;
import com.gakdo.trace.rental.device.domain.DeviceStatus;
import com.gakdo.trace.rental.devicemodel.api.DeviceModelController;
import com.gakdo.trace.rental.devicemodel.application.DeviceModelService;
import com.gakdo.trace.rental.support.Fixtures;
import com.gakdo.trace.rental.support.TestJwts;
import com.gakdo.trace.rental.support.WebTestBase;

// (도구) @WebMvcTest = 서버 전체 대신 지정한 컨트롤러(기기·기종 API)만 띄우는 가벼운 API 테스트. WebTestBase로 운영 보안 규칙도 함께 띄운다
@WebMvcTest(controllers = {DeviceController.class, DeviceModelController.class})
class DeviceApiTest extends WebTestBase {

    // (도구) MockMvc = 진짜 네트워크 없이 서버에 HTTP 요청을 보낸 것처럼 흉내 내는 도구. @Autowired = 스프링이 만들어 넣어 준다
    @Autowired
    private MockMvc mvc;

    // (도구) @MockitoBean = 진짜 서비스 대신 가짜(목) 서비스를 스프링에 끼워 넣는다. 여기선 기기 서비스를 가짜로
    @MockitoBean
    private DeviceService devices;

    // 기종 서비스도 가짜로 끼워 넣는다
    @MockitoBean
    private DeviceModelService models;

    // [상황] 관리자가 기기 상세를 보고, 없는 기기를 보고, 고객이 본다.
    // [기대] 관리자는 계약 모양 그대로 받고, 없으면 404, 고객은 403.
    //        ※ 응답 코드: 404 = 찾을 수 없음(없는 주소나 데이터), 403 = 권한 없음(누구인지는 알지만 허락되지 않은 일)
    // (도구) @Test = JUnit에게 "이 메서드가 테스트 하나"라고 알리는 표시. @DisplayName = 결과 화면에 보일 한글 이름
    @Test
    @DisplayName("기기 상세: ADMIN은 계약 Device 형식을 받고, 없는 기기는 404 NOT_FOUND, CUSTOMER는 403")
    void getDevice() throws Exception {
        // 준비: 테스트 기기(시리얼 C02EXAMPLE01)를 만든다
        Device device = Fixtures.device("C02EXAMPLE01");
        // (도구) when(가짜.메서드(값)).thenReturn(결과) = "가짜에게 이 질문이 오면 이 결과를 돌려줘"라고 미리 정해 두는 것
        // 준비: 가짜 기기 서비스가 이 기기 ID로 조회하면 위 기기를 돌려주게 한다
        when(devices.get(device.getPublicId())).thenReturn(device);
        // 준비: 다른 ID로 조회하면 "기기를 찾을 수 없음" 에러를 던지게 한다 (thenThrow = 결과 대신 예외)
        when(devices.get("IPv6mcQOlTkKj6mOecAQt")).thenThrow(com.gakdo.trace.rental.common.domain.DomainException.notFound("기기"));
        // (도구) perform(get(주소)) = GET 요청을 보낸다. with(admin()) = 관리자 토큰을 붙인다. andExpect(...) = 응답이 이래야 한다는 검사
        // 실행: 관리자로 기기 상세를 조회한다
        mvc.perform(get("/api/v1/devices/" + device.getPublicId()).with(admin()))
                // 확인: 응답 코드가 200(성공)이어야 한다
                .andExpect(status().isOk())
                // (도구) jsonPath("$.칸") = 응답 JSON에서 그 칸의 값을 꺼낸다
                // 확인: 응답의 시리얼이 맞아야 한다
                .andExpect(jsonPath("$.serial").value("C02EXAMPLE01"))
                // 확인: 기종 이름도 함께 내려와야 한다 (계약 Device 형식)
                .andExpect(jsonPath("$.modelName").value("MacBook Air (M2, 2022)"));
        // 실행: 관리자로 없는 기기를 조회한다
        mvc.perform(get("/api/v1/devices/IPv6mcQOlTkKj6mOecAQt").with(admin()))
                // 확인: 응답 코드가 404여야 한다
                .andExpect(status().isNotFound())
                // 확인: 에러 코드가 NOT_FOUND여야 한다
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        // 실행: 고객 토큰으로 기기 상세를 조회한다
        mvc.perform(get("/api/v1/devices/" + device.getPublicId()).with(customer()))
                // 확인: 기기 관리는 관리자만이니 403이어야 한다
                .andExpect(status().isForbidden());
    }

    // [상황] 관리자가 새 노트북을 등록한다.
    // [기대] 201과 기기 정보(기기 ID는 공개 ID)를 돌려준다.
    //        ※ 응답 코드: 201 = 새로 만들어짐
    @Test
    @DisplayName("기기 등록은 201과 계약 Device 형식(deviceId는 publicId)을 돌려준다")
    void registerDevice() throws Exception {
        // 준비: 등록 결과로 돌려줄 테스트 기기를 만든다
        Device device = Fixtures.device("C02EXAMPLE01");
        // 준비: 가짜 서비스가 이 값들(시리얼·기종·OS·색·자산번호·관리자 ID)로 등록하면
        when(devices.register("C02EXAMPLE01", "MBA13-M2", DeviceOs.MACOS, "미드나이트", "A-0001", TestJwts.ADMIN_ID))
                // 위 기기를 돌려주게 한다
                .thenReturn(device);
        // 실행: 관리자로 기기 등록 POST 요청을 JSON 본문과 함께 보낸다 (""" … """ = 여러 줄 글자)
        mvc.perform(post("/api/v1/devices").with(admin()).contentType(MediaType.APPLICATION_JSON).content("""
                {"serial":"C02EXAMPLE01","modelCode":"MBA13-M2","os":"MACOS","color":"미드나이트","assetNo":"A-0001"}
                """))
                // 확인: 응답 코드가 201(새로 만들어짐)이어야 한다
                .andExpect(status().isCreated())
                // 확인: 응답의 기기 ID가 공개 ID여야 한다
                .andExpect(jsonPath("$.deviceId").value(device.getPublicId()))
                // 확인: 기종 이름이 함께 내려와야 한다
                .andExpect(jsonPath("$.modelName").value("MacBook Air (M2, 2022)"))
                // 확인: 새 기기 상태는 사용 가능(ACTIVE)이어야 한다
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                // 확인: 폐기 시각은 비어 있어야 한다
                .andExpect(jsonPath("$.retiredAt").isEmpty())
                // 확인: DB 내부 번호(id)는 응답에 아예 없어야 한다 (doesNotExist = 칸이 없어야 함, 내부 번호 숨김)
                .andExpect(jsonPath("$.id").doesNotExist());
    }

    // [상황] 이미 있는 일련번호로 등록한다.
    // [기대] 409 DEVICE_SERIAL_EXISTS.
    //        ※ 응답 코드: 409 = 지금 상태와 맞지 않아 거절(이미 있음·순서 어긋남 등)
    @Test
    @DisplayName("같은 시리얼로 기기를 등록하면 409 DEVICE_SERIAL_EXISTS(Problem Details)")
    void duplicateSerial() throws Exception {
        // 준비: 가짜 서비스가 어떤 값으로 등록하든 (any = 아무 값)
        when(devices.register(any(), any(), any(), any(), any(), any()))
                // "시리얼 중복" 에러를 던지게 한다
                .thenThrow(new DomainException(ErrorCode.DEVICE_SERIAL_EXISTS, "중복"));
        // 실행: 관리자로 기기 등록 요청을 보낸다
        mvc.perform(post("/api/v1/devices").with(admin()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"serial\":\"C02EXAMPLE01\",\"modelCode\":\"MBA13-M2\",\"os\":\"MACOS\"}"))
                // 확인: 응답 코드가 409여야 한다
                .andExpect(status().isConflict())
                // 확인: 응답 형식이 오류 표준 형식(application/problem+json, Problem Details)이어야 한다
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                // 확인: 에러 코드가 DEVICE_SERIAL_EXISTS여야 한다
                .andExpect(jsonPath("$.code").value("DEVICE_SERIAL_EXISTS"))
                // 확인: instance 칸(어느 주소에서 난 오류인지)이 등록 주소여야 한다
                .andExpect(jsonPath("$.instance").value("/api/v1/devices"));
    }

    // [상황] 모르는 칸, 빠진 칸, 틀린 코드 값(예: 없는 OS)으로 등록한다.
    // [기대] 400 VALIDATION_FAILED.
    //        ※ 응답 코드: 400 = 요청 내용이 형식에 맞지 않음
    @Test
    @DisplayName("등록 본문의 모르는 필드·빠진 필드·틀린 코드 값은 400 VALIDATION_FAILED")
    void invalidCreateBody() throws Exception {
        // 실행: 등록 본문에 모르는 칸 status를 넣어 보낸다 (상태는 등록 때 정할 수 없음)
        mvc.perform(post("/api/v1/devices").with(admin()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"serial\":\"S\",\"modelCode\":\"MBA13-M2\",\"os\":\"MACOS\",\"status\":\"RETIRED\"}"))
                // 확인: 응답 코드가 400이어야 한다
                .andExpect(status().isBadRequest())
                // 확인: 에러 코드가 VALIDATION_FAILED여야 한다
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                // 확인: 칸별 오류 목록의 첫 칸 이름이 status여야 한다
                .andExpect(jsonPath("$.errors[0].field").value("status"));
        // 실행: 시리얼을 빼고, 기종 코드를 틀린 모양(소문자 mba)으로 보낸다
        mvc.perform(post("/api/v1/devices").with(admin()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"modelCode\":\"mba\",\"os\":\"MACOS\"}"))
                // 확인: 응답 코드가 400이어야 한다
                .andExpect(status().isBadRequest())
                // 확인: 칸별 오류가 2개(시리얼 빠짐, 기종 코드 모양)여야 한다 (length() = 목록 길이)
                .andExpect(jsonPath("$.errors.length()").value(2));
        // 실행: 없는 OS 값 LINUX로 보낸다
        mvc.perform(post("/api/v1/devices").with(admin()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"serial\":\"S\",\"modelCode\":\"MBA13-M2\",\"os\":\"LINUX\"}"))
                // 확인: 응답 코드가 400이어야 한다
                .andExpect(status().isBadRequest())
                // 확인: 오류 칸이 os여야 한다
                .andExpect(jsonPath("$.errors[0].field").value("os"));
        // (도구) verifyNoInteractions(가짜) = 그 가짜가 한 번도 불리지 않았어야 한다는 검사
        // 확인: 형식이 틀린 요청은 서비스까지 가지 않았어야 한다
        verifyNoInteractions(devices);
    }

    // [상황] 기기 수정에 일련번호를 넣어 보낸다.
    // [기대] 400. 일련번호는 등록 뒤 바꿀 수 없다(기록 바꿔치기 방지).
    //        ※ 응답 코드: 400 = 요청 내용이 형식에 맞지 않음
    @Test
    @DisplayName("PATCH에 serial을 보내면 400이고 서비스는 불리지 않는다(시리얼은 등록 뒤 바꿀 수 없다)")
    void patchSerialRejected() throws Exception {
        // 실행: 관리자로 기기 수정(PATCH) 본문에 시리얼을 넣어 보낸다 (PATCH = 일부만 고치는 요청)
        mvc.perform(patch("/api/v1/devices/IPv6mcQOlTkKj6mOecAQt").with(admin())
                .contentType(MediaType.APPLICATION_JSON).content("{\"serial\":\"NEW\"}"))
                // 확인: 응답 코드가 400이어야 한다
                .andExpect(status().isBadRequest())
                // 확인: 오류 칸이 serial이어야 한다
                .andExpect(jsonPath("$.errors[0].field").value("serial"));
        // 확인: 서비스는 불리지 않았어야 한다 (시리얼 바꿔치기 차단)
        verifyNoInteractions(devices);
    }

    // [상황] 진행 중 렌탈이 있는 기기를 수리 중으로 바꾼다.
    // [기대] 409 DEVICE_HAS_OPEN_RENTAL.
    //        ※ 응답 코드: 409 = 지금 상태와 맞지 않아 거절(이미 있음·순서 어긋남 등)
    @Test
    @DisplayName("진행 중 렌탈이 있는 기기를 수리 중으로 바꾸면 409 DEVICE_HAS_OPEN_RENTAL")
    void patchWithOpenRental() throws Exception {
        // 준비: 가짜 서비스가 이 기기를 관리자 ID로 고치려 하면 (eq = 정확히 이 값)
        when(devices.change(eq("IPv6mcQOlTkKj6mOecAQt"), any(), eq(TestJwts.ADMIN_ID)))
                // "진행 중 렌탈 있음" 에러를 던지게 한다
                .thenThrow(new DomainException(ErrorCode.DEVICE_HAS_OPEN_RENTAL, "렌탈 중"));
        // 실행: 상태를 수리 중으로 바꾸는 PATCH 요청을 보낸다
        mvc.perform(patch("/api/v1/devices/IPv6mcQOlTkKj6mOecAQt").with(admin())
                .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"MAINTENANCE\"}"))
                // 확인: 응답 코드가 409여야 한다
                .andExpect(status().isConflict())
                // 확인: 에러 코드가 DEVICE_HAS_OPEN_RENTAL이어야 한다 (빌려 간 기기를 수리 중으로 돌리지 못함)
                .andExpect(jsonPath("$.code").value("DEVICE_HAS_OPEN_RENTAL"));
    }

    // [상황] 폐기 기기를 바로 사용 가능으로 바꾼다.
    // [기대] 409 DEVICE_STATUS_TRANSITION_INVALID.
    //        ※ 응답 코드: 409 = 지금 상태와 맞지 않아 거절(이미 있음·순서 어긋남 등)
    @Test
    @DisplayName("폐기 기기를 사용 가능으로 바로 바꾸면 409 DEVICE_STATUS_TRANSITION_INVALID(Problem Details)")
    void patchRetiredToActive() throws Exception {
        // 준비: 가짜 서비스가 이 기기를 고치려 하면
        when(devices.change(eq("IPv6mcQOlTkKj6mOecAQt"), any(), eq(TestJwts.ADMIN_ID)))
                // "허용되지 않는 상태 변경" 에러를 던지게 한다
                .thenThrow(new DomainException(ErrorCode.DEVICE_STATUS_TRANSITION_INVALID, "수리 중을 거쳐야 함"));
        // 실행: 상태를 사용 가능(ACTIVE)으로 바꾸는 PATCH 요청을 보낸다
        mvc.perform(patch("/api/v1/devices/IPv6mcQOlTkKj6mOecAQt").with(admin())
                .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"ACTIVE\"}"))
                // 확인: 응답 코드가 409여야 한다
                .andExpect(status().isConflict())
                // 확인: 응답 형식이 Problem Details여야 한다
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                // 확인: 에러 코드가 DEVICE_STATUS_TRANSITION_INVALID여야 한다
                .andExpect(jsonPath("$.code").value("DEVICE_STATUS_TRANSITION_INVALID"))
                // 확인: 제목(title)이 화면에 보여 줄 한국어 안내 문구여야 한다
                .andExpect(jsonPath("$.title").value("폐기한 기기는 수리 중으로만 되돌릴 수 있습니다"));
    }

    // [상황] 기종·상태로 기기 목록을 찾는다.
    // [기대] 결과와 다음 쪽 커서를 준다.
    @Test
    @DisplayName("기기 목록은 기종·상태로 찾고 nextCursor를 돌려준다")
    void searchDevices() throws Exception {
        // 준비: 검색 결과로 돌려줄 테스트 기기를 만든다
        Device device = Fixtures.device("C02EXAMPLE01");
        // 준비: 가짜 서비스가 기종 MBA13-M2·상태 ACTIVE·나머지 조건 없음(isNull)·개수 1로 검색하면
        when(devices.search(eq("MBA13-M2"), eq(DeviceStatus.ACTIVE), isNull(), isNull(), eq(1)))
                // 기기 1대와 다음 쪽 커서 "next"를 돌려주게 한다
                .thenReturn(new DeviceService.Page(List.of(device), "next"));
        // 실행: 관리자로 기종·상태·개수 조건을 붙여 목록을 조회한다
        mvc.perform(get("/api/v1/devices?modelCode=MBA13-M2&status=ACTIVE&limit=1").with(admin()))
                // 확인: 응답 코드가 200이어야 한다
                .andExpect(status().isOk())
                // 확인: 목록 첫 기기의 시리얼이 맞아야 한다
                .andExpect(jsonPath("$.items[0].serial").value("C02EXAMPLE01"))
                // 확인: 다음 쪽 커서가 "next"로 내려와야 한다 (이어서 다음 쪽을 부를 수 있게)
                .andExpect(jsonPath("$.nextCursor").value("next"));
    }

    // [상황] 개수 0, 없는 상태 값, 깨진 커서로 찾는다.
    // [기대] 400.
    //        ※ 응답 코드: 400 = 요청 내용이 형식에 맞지 않음
    @Test
    @DisplayName("목록 파라미터 형식 오류(limit 0, 상태 값, 깨진 커서)는 400")
    void searchInvalidParams() throws Exception {
        // 실행+확인: 개수 0은 허용 범위(1~100) 밖이라 400이어야 한다
        mvc.perform(get("/api/v1/devices?limit=0").with(admin())).andExpect(status().isBadRequest());
        // 실행+확인: 개수 101도 범위 밖이라 400이어야 한다
        mvc.perform(get("/api/v1/devices?limit=101").with(admin())).andExpect(status().isBadRequest());
        // 실행: 없는 상태 값 LOST로 조회한다
        mvc.perform(get("/api/v1/devices?status=LOST").with(admin()))
                // 확인: 응답 코드가 400이어야 한다
                .andExpect(status().isBadRequest())
                // 확인: 오류 칸이 status여야 한다
                .andExpect(jsonPath("$.errors[0].field").value("status"));
        // 실행: 깨진 커서(@@@)로 조회한다
        mvc.perform(get("/api/v1/devices?cursor=@@@").with(admin()))
                // 확인: 응답 코드가 400이어야 한다
                .andExpect(status().isBadRequest())
                // 확인: 오류 칸이 cursor여야 한다
                .andExpect(jsonPath("$.errors[0].field").value("cursor"));
    }

    // [상황] 로그인 없이, 고객으로 기기 API를 부른다.
    // [기대] 로그인 없음 401, 고객 403(기기 관리는 관리자만).
    //        ※ 응답 코드: 401 = 로그인 필요(토큰이 없거나 만료·위조됨), 403 = 권한 없음(누구인지는 알지만 허락되지 않은 일)
    @Test
    @DisplayName("기기 API는 ADMIN만: 토큰 없으면 401 UNAUTHENTICATED, CUSTOMER면 403 FORBIDDEN")
    void deviceAccess() throws Exception {
        // 실행: 로그인 토큰 없이 기기 목록을 조회한다
        mvc.perform(get("/api/v1/devices"))
                // 확인: 응답 코드가 401(로그인 필요)이어야 한다
                .andExpect(status().isUnauthorized())
                // 확인: 에러 코드가 UNAUTHENTICATED여야 한다
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        // 실행: 고객 토큰으로 기기 목록을 조회한다
        mvc.perform(get("/api/v1/devices").with(customer()))
                // 확인: 응답 코드가 403(권한 없음)이어야 한다
                .andExpect(status().isForbidden())
                // 확인: 에러 코드가 FORBIDDEN이어야 한다
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        // 실행+확인: 부스 토큰으로 조회해도 403이어야 한다
        mvc.perform(get("/api/v1/devices").with(TestJwts.booth())).andExpect(status().isForbidden());
    }

    // [상황] 기종 코드로 치수를 조회한다(고객도).
    // [기대] 3D 트윈 화면에 쓸 치수를 고객도 받을 수 있다.
    @Test
    @DisplayName("모델명으로 3D 트윈 치수를 조회한다(CUSTOMER도 가능)")
    void getModelDimensions() throws Exception {
        // 준비: 가짜 기종 서비스가 MBA13-M2를 조회하면 견본 기종을 돌려주게 한다
        when(models.get("MBA13-M2")).thenReturn(Fixtures.model());
        // 실행: 고객 토큰으로 기종 상세를 조회한다
        mvc.perform(get("/api/v1/device-models/MBA13-M2").with(customer()))
                // 확인: 고객도 볼 수 있어 200이어야 한다 (3D 트윈 화면에 치수가 필요)
                .andExpect(status().isOk())
                // 확인: 치수의 가로가 304.1mm여야 한다
                .andExpect(jsonPath("$.dimensions.widthMm").value(304.1))
                // 확인: 덮개 두께가 4.3mm여야 한다
                .andExpect(jsonPath("$.dimensions.lidHMm").value(4.3))
                // 확인: 사양의 RAM이 8GB여야 한다
                .andExpect(jsonPath("$.spec.ramGb").value(8))
                // 확인: 형태가 접는 노트북(CLAMSHELL)이어야 한다
                .andExpect(jsonPath("$.formFactor").value("CLAMSHELL"));
    }

    // [상황] 관리자가 기종 목록을 보고 기종을 저장(있으면 고치고 없으면 만듦)한다.
    // [기대] 목록은 관리자만, 저장할 때 누가 바꿨는지 함께 넘긴다.
    @Test
    @DisplayName("기종 목록은 ADMIN만, 저장은 PUT 업서트이고 변경자를 넘긴다")
    void modelListAndUpsert() throws Exception {
        // 준비: 가짜 기종 서비스가 목록을 달라고 하면 견본 기종 1개를 돌려주게 한다
        when(models.list()).thenReturn(List.of(Fixtures.model()));
        // 실행: 관리자로 기종 목록을 조회한다
        mvc.perform(get("/api/v1/device-models").with(admin()))
                // 확인: 응답 코드가 200이어야 한다
                .andExpect(status().isOk())
                // 확인: 첫 기종 코드가 MBA13-M2여야 한다
                .andExpect(jsonPath("$.items[0].modelCode").value("MBA13-M2"))
                // 확인: 사양의 CPU가 Apple M2여야 한다
                .andExpect(jsonPath("$.items[0].spec.cpu").value("Apple M2"))
                // 확인: 값이 없는 GPU 칸은 응답에서 빠져 있어야 한다
                .andExpect(jsonPath("$.items[0].spec.gpu").doesNotExist())
                // 확인: 목록에는 치수를 싣지 않아야 한다 (상세 조회에서만 보냄)
                .andExpect(jsonPath("$.items[0].dimensions").doesNotExist());
        // 실행+확인: 고객이 기종 목록을 보면 403이어야 한다 (목록은 관리자만)
        mvc.perform(get("/api/v1/device-models").with(customer())).andExpect(status().isForbidden());

        // 준비: 가짜 서비스가 MBA13-M2를 관리자 ID와 함께 저장하면 견본 기종을 돌려주게 한다 (변경자가 넘어가는지도 함께 확인됨)
        when(models.upsert(eq("MBA13-M2"), any(), eq(TestJwts.ADMIN_ID))).thenReturn(Fixtures.model());
        // 실행: 관리자로 기종 저장(PUT = 있으면 고치고 없으면 만듦) 요청을 JSON 본문과 함께 보낸다
        mvc.perform(put("/api/v1/device-models/MBA13-M2").with(admin()).contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"name":"MacBook Air (M2, 2022)","manufacturer":"Apple","formFactor":"CLAMSHELL",
                         "spec":{"cpu":"Apple M2","ramGb":8,"ssdGb":256},
                         "dimensions":{"widthMm":304.1,"depthMm":215.0,"baseHMm":7.0,"lidHMm":4.3}}
                        """))
                // 확인: 응답 코드가 200이어야 한다
                .andExpect(status().isOk())
                // 확인: 응답의 기종 코드가 MBA13-M2여야 한다
                .andExpect(jsonPath("$.modelCode").value("MBA13-M2"));
    }

    // [상황] 치수 0 이하·빠진 치수·잘못된 기종 코드로 저장하고, 없는 기종을 조회한다.
    // [기대] 저장은 400, 없는 기종 조회는 404.
    //        ※ 응답 코드: 400 = 요청 내용이 형식에 맞지 않음, 404 = 찾을 수 없음(없는 주소나 데이터)
    @Test
    @DisplayName("기종 저장: 치수 0 이하·빠진 치수·잘못된 기종 코드는 400, 없는 기종 조회는 404")
    void modelValidation() throws Exception {
        // 실행: 가로 0, 덮개 두께 빠짐인 치수로 기종을 저장한다
        mvc.perform(put("/api/v1/device-models/MBA13-M2").with(admin()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"X\",\"formFactor\":\"CLAMSHELL\",\"dimensions\":{\"widthMm\":0,\"depthMm\":1,\"baseHMm\":1}}"))
                // 확인: 응답 코드가 400이어야 한다
                .andExpect(status().isBadRequest())
                // 확인: 칸별 오류가 2개(가로 0 이하, 덮개 두께 빠짐)여야 한다
                .andExpect(jsonPath("$.errors.length()").value(2));
        // 실행+확인: 기종 코드 모양이 틀리면(소문자) 400이어야 한다
        mvc.perform(get("/api/v1/device-models/mba13").with(admin())).andExpect(status().isBadRequest());
        // 준비: 가짜 서비스가 NONE-1을 조회하면 "기종 없음" 에러를 던지게 한다
        when(models.get("NONE-1")).thenThrow(DomainException.notFound("기종"));
        // 실행: 관리자로 없는 기종을 조회한다
        mvc.perform(get("/api/v1/device-models/NONE-1").with(admin()))
                // 확인: 응답 코드가 404여야 한다
                .andExpect(status().isNotFound())
                // 확인: 에러 코드가 NOT_FOUND여야 한다
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    // [상황] 기종 사양에 GPU를 넣거나 빼고, 128자를 넘겨 넣는다.
    // [기대] GPU는 선택 항목으로 저장되고, 너무 길면 400.
    //        ※ 응답 코드: 400 = 요청 내용이 형식에 맞지 않음
    @Test
    @DisplayName("기준 사양에 GPU를 선택으로 저장하고, 128자를 넘으면 400")
    void modelSpecGpu() throws Exception {
        // (도구) ArgumentCaptor = 가짜 메서드에 실제로 넘어간 값을 붙잡아 두는 도구 (나중에 내용 검사용)
        // 준비: 기종 저장에 넘어간 기종 속성을 붙잡을 그릇을 만든다
        var captor = org.mockito.ArgumentCaptor.forClass(
                com.gakdo.trace.rental.devicemodel.domain.DeviceModel.Attributes.class);
        // 준비: GRAM16-2024를 저장하면 넘어간 속성을 붙잡아 두고 견본 기종을 돌려주게 한다
        when(models.upsert(eq("GRAM16-2024"), captor.capture(), eq(TestJwts.ADMIN_ID))).thenReturn(Fixtures.model());
        // 실행: GPU가 들어간 LG 그램 16 기종을 저장한다
        mvc.perform(put("/api/v1/device-models/GRAM16-2024").with(admin()).contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"name":"LG 그램 16","manufacturer":"LG","formFactor":"CLAMSHELL",
                         "spec":{"cpu":"Intel Core Ultra 7 155H","ramGb":16,"ssdGb":512,"gpu":"Intel Arc Graphics"},
                         "dimensions":{"widthMm":357.0,"depthMm":250.0,"baseHMm":12.0,"lidHMm":5.0}}
                        """))
                // 확인: 응답 코드가 200이어야 한다
                .andExpect(status().isOk());
        // (도구) assertThat(실제값).isEqualTo(기대값) = "실제값이 기대값과 같아야 한다"는 검사
        // 확인: 서비스에 넘어간 사양의 GPU가 보낸 값 그대로여야 한다
        org.assertj.core.api.Assertions.assertThat(captor.getValue().spec().gpu()).isEqualTo("Intel Arc Graphics");

        // 실행: GPU 이름을 129자(한도 128자 초과)로 넣어 저장한다
        mvc.perform(put("/api/v1/device-models/GRAM16-2024").with(admin()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"X\",\"formFactor\":\"CLAMSHELL\",\"spec\":{\"gpu\":\"" + "G".repeat(129)
                        + "\"},\"dimensions\":{\"widthMm\":1,\"depthMm\":1,\"baseHMm\":1,\"lidHMm\":1}}"))
                // 확인: 너무 길어 400이어야 한다
                .andExpect(status().isBadRequest());
    }

    // [상황] 관리자가 기기·기종을 지우고, 이력이 있는 것을 지우고, 고객이 지워 본다.
    // [기대] 관리자 정상 삭제 204, 이력이 있으면 409(DEVICE_IN_USE·DEVICE_MODEL_IN_USE), 고객 403.
    //        ※ 응답 코드: 204 = 처리됨, 돌려줄 내용 없음, 409 = 지금 상태와 맞지 않아 거절(이미 있음·순서 어긋남 등),
    //          403 = 권한 없음(누구인지는 알지만 허락되지 않은 일)
    @Test
    @DisplayName("기기·기종 지우기: ADMIN은 204, 이력이 있으면 409(DEVICE_IN_USE·DEVICE_MODEL_IN_USE), CUSTOMER는 403")
    void deleteDeviceAndModel() throws Exception {
        // 실행+확인: 관리자가 기기를 지우면 204(처리됨, 내용 없음)여야 한다
        mvc.perform(delete("/api/v1/devices/aAXQFO2Vz6ZTFbPctC2dq").with(admin())).andExpect(status().isNoContent());
        // (도구) verify(가짜).메서드(값) = 가짜의 그 메서드가 이 값으로 불렸어야 한다는 검사
        // 확인: 기기 서비스의 지우기가 이 기기 ID로 불렸어야 한다
        org.mockito.Mockito.verify(devices).delete("aAXQFO2Vz6ZTFbPctC2dq");
        // (도구) doThrow(예외).when(가짜).메서드(값) = 그 메서드가 불리면 이 예외를 던지게 정해 두는 것 (돌려줄 값 없는 메서드용)
        // 준비: 이력이 있는 기기 ID를 지우면 "사용 중인 기기" 에러를 던지게 한다
        org.mockito.Mockito.doThrow(new DomainException(ErrorCode.DEVICE_IN_USE, "이력 있음")).when(devices).delete("Xs0HLv6QRbDILPWOcz5I2");
        // 실행: 관리자로 이력 있는 기기를 지운다
        mvc.perform(delete("/api/v1/devices/Xs0HLv6QRbDILPWOcz5I2").with(admin()))
                // 확인: 409이고 에러 코드가 DEVICE_IN_USE여야 한다
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DEVICE_IN_USE"));

        // 실행+확인: 관리자가 기종을 지우면 204여야 한다
        mvc.perform(delete("/api/v1/device-models/TEST-MODEL").with(admin())).andExpect(status().isNoContent());
        // 확인: 기종 서비스의 지우기가 TEST-MODEL로 불렸어야 한다
        org.mockito.Mockito.verify(models).delete("TEST-MODEL");
        // 준비: 기기가 있는 기종 MBA13-M2를 지우면 "사용 중인 기종" 에러를 던지게 한다
        org.mockito.Mockito.doThrow(new DomainException(ErrorCode.DEVICE_MODEL_IN_USE, "기기 있음")).when(models).delete("MBA13-M2");
        // 실행: 관리자로 기기가 있는 기종을 지운다
        mvc.perform(delete("/api/v1/device-models/MBA13-M2").with(admin()))
                // 확인: 409이고 에러 코드가 DEVICE_MODEL_IN_USE여야 한다
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DEVICE_MODEL_IN_USE"));

        // 실행+확인: 고객이 기기를 지우면 403이어야 한다
        mvc.perform(delete("/api/v1/devices/aAXQFO2Vz6ZTFbPctC2dq").with(customer())).andExpect(status().isForbidden());
        // 실행+확인: 고객이 기종을 지우면 403이어야 한다
        mvc.perform(delete("/api/v1/device-models/TEST-MODEL").with(customer())).andExpect(status().isForbidden());
        // 실행+확인: 기종 코드 모양이 틀리면(공백 포함) 400이어야 한다
        mvc.perform(delete("/api/v1/device-models/bad code").with(admin())).andExpect(status().isBadRequest());
    }
}
