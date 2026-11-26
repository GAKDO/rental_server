// 이 파일이 확인하는 것: 부스 코드 API. 관리자가 렌탈에 6자리 코드를 발급하면, 부스 태블릿에서 그 코드를 넣어
// 어떤 렌탈의 어느 시점을 찍을지 고른다(태블릿에 렌탈 목록을 다 보여 주지 않아도 됨).

package com.gakdo.trace.rental.boothcode;

import static com.gakdo.trace.rental.support.TestJwts.admin;
import static com.gakdo.trace.rental.support.TestJwts.booth;
import static com.gakdo.trace.rental.support.TestJwts.customer;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.gakdo.trace.rental.boothcode.api.BoothCodeController;
import com.gakdo.trace.rental.boothcode.application.BoothCodeService;
import com.gakdo.trace.rental.common.domain.DomainException;
import com.gakdo.trace.rental.common.domain.ErrorCode;
import com.gakdo.trace.rental.common.domain.RateLimitedException;
import com.gakdo.trace.rental.rental.domain.CheckpointType;
import com.gakdo.trace.rental.support.WebTestBase;

// (도구) @WebMvcTest = 서버 전체 대신 지정한 컨트롤러(BoothCodeController)만 띄우는 가벼운 API 테스트. WebTestBase로 운영 보안 규칙도 함께 띄운다
@WebMvcTest(controllers = BoothCodeController.class)
class BoothCodeApiTest extends WebTestBase {

    // 테스트에 쓸 렌탈 공개 ID (21자)
    private static final String RENTAL = "Ws0HLv6QRbDILPWOcz5I2";
    // 부스가 코드를 넣는(조회하는) API 주소
    private static final String REDEEM = "/api/v1/rentals/booth-codes/redeem";

    // (도구) MockMvc = 진짜 네트워크 없이 서버에 HTTP 요청을 보낸 것처럼 흉내 내는 도구. @Autowired = 스프링이 만들어 넣어 준다
    @Autowired
    private MockMvc mvc;

    // (도구) @MockitoBean = 진짜 서비스 대신 가짜(목) 서비스를 스프링에 끼워 넣는다. API 입구(권한·형식 검사)만 따로 시험하려고
    @MockitoBean
    private BoothCodeService service;

    // [상황] 관리자가 렌탈에 부스 코드를 발급한다.
    // [기대] 201과 6자리 코드·만료 시각·묶인 시점(출고)을 받는다.
    //        ※ 응답 코드: 201 = 새로 만들어짐
    // (도구) @Test = JUnit에게 "이 메서드가 테스트 하나"라고 알리는 표시. @DisplayName = 결과 화면에 보일 한글 이름
    @Test
    @DisplayName("관리자가 발급하면 201과 6자리 코드·만료 시각·묶인 시점을 받는다")
    void adminIssues() throws Exception {
        // (도구) when(가짜.메서드(값)).thenReturn(결과) = "가짜에게 이 질문이 오면 이 결과를 돌려줘"라고 미리 정해 두는 것
        // 준비: 가짜 서비스가 이 렌탈에 코드를 발급하면 코드 305718·만료 시각·출고 시점 ID·시점 종류 OUT을 돌려주게 한다
        when(service.issue(RENTAL)).thenReturn(new BoothCodeService.Issued("305718",
                OffsetDateTime.parse("2026-11-12T00:10:00Z"), "aJ3bYet82WYF8iVlHstT2", CheckpointType.OUT));
        // (도구) perform(요청) = 요청을 보낸다. with(admin()) = 관리자 로그인 토큰을 붙인다. andExpect(...) = 응답이 이래야 한다는 검사
        // 실행: 관리자로 이 렌탈의 부스 코드 발급 POST 요청을 보낸다
        mvc.perform(post("/api/v1/rentals/" + RENTAL + "/booth-code").with(admin()))
                // 확인: 응답 코드가 201(새로 만들어짐)이어야 한다
                .andExpect(status().isCreated())
                // (도구) jsonPath("$.칸") = 응답 JSON에서 그 칸의 값을 꺼낸다
                // 확인: 응답의 코드가 6자리 305718이어야 한다
                .andExpect(jsonPath("$.code").value("305718"))
                // 확인: 코드에 묶인 시점 ID가 응답에 있어야 한다
                .andExpect(jsonPath("$.checkpointId").value("aJ3bYet82WYF8iVlHstT2"))
                // 확인: 묶인 시점 종류가 출고(OUT)여야 한다
                .andExpect(jsonPath("$.checkpointType").value("OUT"))
                // 확인: 만료 시각 칸이 있어야 한다 (exists = 칸이 있기만 하면 통과)
                .andExpect(jsonPath("$.expiresAt").exists());
    }

    // [상황] 부스·고객이 발급하려 하고, 관리자·고객이 코드를 넣으려 하고, 로그인 없이 넣으려 한다.
    // [기대] 발급은 관리자만, 코드 넣기는 부스만. 나머지는 403, 로그인 없으면 401.
    //        ※ 응답 코드: 403 = 권한 없음(누구인지는 알지만 허락되지 않은 일), 401 = 로그인 필요(토큰이 없거나 만료·위조됨)
    @Test
    @DisplayName("부스·고객은 코드를 발급할 수 없고, 관리자·고객은 코드를 조회할 수 없다(403)")
    void rolesAreSeparated() throws Exception {
        // 실행+확인: 부스 토큰으로 발급하면 403(권한 없음)이어야 한다
        mvc.perform(post("/api/v1/rentals/" + RENTAL + "/booth-code").with(booth())).andExpect(status().isForbidden());
        // 실행: 이 렌탈의 주인인 고객 토큰으로 발급을 시도한다
        mvc.perform(post("/api/v1/rentals/" + RENTAL + "/booth-code").with(customer(RENTAL)))
                // 확인: 고객도 발급할 수 없어 403이어야 한다
                .andExpect(status().isForbidden());
        // 실행+확인: 관리자가 코드를 넣어(조회해) 보면 403이어야 한다 (코드 넣기는 부스만)
        mvc.perform(redeem("305718").with(admin())).andExpect(status().isForbidden());
        // 실행+확인: 고객이 코드를 넣어 봐도 403이어야 한다
        mvc.perform(redeem("305718").with(customer(RENTAL))).andExpect(status().isForbidden());
        // 실행: 로그인 토큰 없이 코드를 넣어 본다
        mvc.perform(post(REDEEM).contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"305718\"}"))
                // 확인: 401(로그인 필요)이어야 한다
                .andExpect(status().isUnauthorized());
        // (도구) verifyNoInteractions(가짜) = 그 가짜가 한 번도 불리지 않았어야 한다는 검사
        // 확인: 모두 입구에서 막혔으니 서비스는 한 번도 불리지 않았어야 한다
        verifyNoInteractions(service);
    }

    // [상황] 부스가 맞는 코드를 넣는다.
    // [기대] 렌탈·기기·일련번호·기종·시점 ID를 받아 바로 촬영을 시작할 수 있다.
    @Test
    @DisplayName("부스가 맞는 코드를 넣으면 기기·렌탈·시점 ID를 받는다")
    void boothRedeems() throws Exception {
        // 준비: 가짜 서비스가 코드 305718을 받으면 렌탈 ID·기기 ID·일련번호·기종 코드·시점 ID·시점 종류를 돌려주게 한다
        when(service.redeem("305718")).thenReturn(new BoothCodeService.Target(RENTAL, "aAXQFO2Vz6ZTFbPctC2dq",
                "C02XG2JHJGH5", "MBA13-M2", "aJ3bYet82WYF8iVlHstT2", CheckpointType.OUT));
        // 실행: 부스 토큰으로 코드 305718을 넣는다
        mvc.perform(redeem("305718").with(booth()))
                // 확인: 응답 코드가 200(성공)이어야 한다
                .andExpect(status().isOk())
                // 확인: 응답의 렌탈 ID가 맞아야 한다
                .andExpect(jsonPath("$.rentalId").value(RENTAL))
                // 확인: 응답의 기기 ID가 맞아야 한다
                .andExpect(jsonPath("$.deviceId").value("aAXQFO2Vz6ZTFbPctC2dq"))
                // 확인: 응답의 일련번호가 맞아야 한다 (부스 화면에서 기기를 확인하려고)
                .andExpect(jsonPath("$.serial").value("C02XG2JHJGH5"))
                // 확인: 응답의 기종 코드가 MBA13-M2여야 한다
                .andExpect(jsonPath("$.modelCode").value("MBA13-M2"))
                // 확인: 찍을 시점 ID가 맞아야 한다
                .andExpect(jsonPath("$.checkpointId").value("aJ3bYet82WYF8iVlHstT2"))
                // 확인: 찍을 시점 종류가 출고(OUT)여야 한다
                .andExpect(jsonPath("$.checkpointType").value("OUT"));
    }

    // [상황] 5자리, 글자 섞인 코드, 모르는 칸(rentalId)을 붙인 요청.
    // [기대] 모두 400(서비스까지 가지 않음).
    //        ※ 응답 코드: 400 = 요청 내용이 형식에 맞지 않음
    @Test
    @DisplayName("코드가 6자리 숫자가 아니거나 모르는 필드가 있으면 400 VALIDATION_FAILED")
    void redeemValidation() throws Exception {
        // 실행: 5자리 코드를 넣고, 응답이 400이어야 한다
        mvc.perform(redeem("30571").with(booth())).andExpect(status().isBadRequest())
                // 확인: 에러 코드가 VALIDATION_FAILED(입력값 오류)여야 한다
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        // 실행+확인: 글자가 섞인 코드도 400이어야 한다
        mvc.perform(redeem("30571a").with(booth())).andExpect(status().isBadRequest());
        // 실행: 모르는 칸 rentalId를 덧붙인 본문을 보낸다
        mvc.perform(post(REDEEM).with(booth()).contentType(MediaType.APPLICATION_JSON)
                // 확인: 모르는 칸이 있으니 400이어야 한다 (부스가 엉뚱한 렌탈을 고르지 못하게)
                .content("{\"code\":\"305718\",\"rentalId\":\"x\"}")).andExpect(status().isBadRequest());
        // 확인: 형식이 틀린 요청은 서비스까지 가지 않았어야 한다
        verifyNoInteractions(service);
    }

    // [상황] 틀린 코드, 만료·이미 쓴 코드, 틀린 시도가 몰려 막힌 상태.
    // [기대] 틀림 400 BOOTH_CODE_INVALID, 만료 410, 막힘 429와 42초 뒤 다시(Retry-After).
    //        ※ 응답 코드: 400 = 요청 내용이 형식에 맞지 않음, 410 = 이미 쓰였거나 만료되어 더는 쓸 수 없음, 429 = 요청이 너무 잦아 잠시 막음
    @Test
    @DisplayName("틀린 코드는 400 BOOTH_CODE_INVALID, 만료·사용된 코드는 410, 막히면 429와 Retry-After")
    void redeemErrors() throws Exception {
        // 준비: 코드 111111이면 "틀린 코드" 에러를 던지게 한다 (thenThrow = 결과 대신 예외를 던짐)
        when(service.redeem("111111")).thenThrow(new DomainException(ErrorCode.BOOTH_CODE_INVALID, "틀림"));
        // 준비: 코드 222222면 "만료된 코드" 에러를 던지게 한다
        when(service.redeem("222222")).thenThrow(new DomainException(ErrorCode.BOOTH_CODE_EXPIRED, "만료"));
        // 준비: 코드 333333이면 "너무 잦아 막힘, 42초 뒤 다시" 에러를 던지게 한다
        when(service.redeem("333333")).thenThrow(new RateLimitedException("막힘", 42));
        // 실행: 틀린 코드를 넣고, 응답이 400이어야 한다
        mvc.perform(redeem("111111").with(booth())).andExpect(status().isBadRequest())
                // 확인: 에러 코드가 BOOTH_CODE_INVALID여야 한다
                .andExpect(jsonPath("$.code").value("BOOTH_CODE_INVALID"));
        // 실행: 만료된 코드를 넣고, 응답이 410(더는 쓸 수 없음)이어야 한다
        mvc.perform(redeem("222222").with(booth())).andExpect(status().isGone())
                // 확인: 에러 코드가 BOOTH_CODE_EXPIRED여야 한다
                .andExpect(jsonPath("$.code").value("BOOTH_CODE_EXPIRED"));
        // 실행: 막힌 상태에서 코드를 넣고, 응답이 429(요청이 너무 잦음)여야 한다
        mvc.perform(redeem("333333").with(booth())).andExpect(status().isTooManyRequests())
                // 확인: 응답 헤더 Retry-After가 42여야 한다 (부스가 42초 뒤에 다시 시도하도록 알림, header() = 응답 머리말 검사)
                .andExpect(header().string("Retry-After", "42"))
                // 확인: 에러 코드가 RATE_LIMITED여야 한다
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
    }

    // [상황] 찍을 시점이 아직 없는 렌탈에 발급한다.
    // [기대] 409 CHECKPOINT_ORDER_VIOLATION(출고 시점부터 시작해야 함).
    //        ※ 응답 코드: 409 = 지금 상태와 맞지 않아 거절(이미 있음·순서 어긋남 등)
    @Test
    @DisplayName("찍을 시점이 없는 렌탈에 발급하면 409 CHECKPOINT_ORDER_VIOLATION")
    void issueConflict() throws Exception {
        // 준비: 가짜 서비스가 이 렌탈에 발급하려 하면 "출고 먼저" 순서 위반 에러를 던지게 한다
        when(service.issue(RENTAL)).thenThrow(new DomainException(ErrorCode.CHECKPOINT_ORDER_VIOLATION, "출고 먼저"));
        // 실행: 관리자로 부스 코드 발급 요청을 보낸다
        mvc.perform(post("/api/v1/rentals/" + RENTAL + "/booth-code").with(admin()))
                // 확인: 응답 코드가 409이고, 에러 코드가 CHECKPOINT_ORDER_VIOLATION이어야 한다
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CHECKPOINT_ORDER_VIOLATION"));
    }

    // 도우미: 받은 코드를 JSON 본문 {"code":"…"}에 담아 코드 넣기 POST 요청을 만든다 (토큰은 부르는 쪽에서 붙임)
    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder redeem(String code) {
        // 코드 넣기 주소로 JSON 본문을 담은 POST 요청을 만들어 돌려준다
        return post(REDEEM).contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"" + code + "\"}");
    }
}
