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

@WebMvcTest(controllers = BoothCodeController.class)
class BoothCodeApiTest extends WebTestBase {

    private static final String RENTAL = "Ws0HLv6QRbDILPWOcz5I2";
    private static final String REDEEM = "/api/v1/rentals/booth-codes/redeem";

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private BoothCodeService service;

    // [상황] 관리자가 렌탈에 부스 코드를 발급한다.
    // [기대] 201과 6자리 코드·만료 시각·묶인 시점(출고)을 받는다.
    //        ※ 응답 코드: 201 = 새로 만들어짐
    @Test
    @DisplayName("관리자가 발급하면 201과 6자리 코드·만료 시각·묶인 시점을 받는다")
    void adminIssues() throws Exception {
        when(service.issue(RENTAL)).thenReturn(new BoothCodeService.Issued("305718",
                OffsetDateTime.parse("2026-11-12T00:10:00Z"), "aJ3bYet82WYF8iVlHstT2", CheckpointType.OUT));
        mvc.perform(post("/api/v1/rentals/" + RENTAL + "/booth-code").with(admin()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("305718"))
                .andExpect(jsonPath("$.checkpointId").value("aJ3bYet82WYF8iVlHstT2"))
                .andExpect(jsonPath("$.checkpointType").value("OUT"))
                .andExpect(jsonPath("$.expiresAt").exists());
    }

    // [상황] 부스·고객이 발급하려 하고, 관리자·고객이 코드를 넣으려 하고, 로그인 없이 넣으려 한다.
    // [기대] 발급은 관리자만, 코드 넣기는 부스만. 나머지는 403, 로그인 없으면 401.
    //        ※ 응답 코드: 403 = 권한 없음(누구인지는 알지만 허락되지 않은 일), 401 = 로그인 필요(토큰이 없거나 만료·위조됨)
    @Test
    @DisplayName("부스·고객은 코드를 발급할 수 없고, 관리자·고객은 코드를 조회할 수 없다(403)")
    void rolesAreSeparated() throws Exception {
        mvc.perform(post("/api/v1/rentals/" + RENTAL + "/booth-code").with(booth())).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/rentals/" + RENTAL + "/booth-code").with(customer(RENTAL)))
                .andExpect(status().isForbidden());
        mvc.perform(redeem("305718").with(admin())).andExpect(status().isForbidden());
        mvc.perform(redeem("305718").with(customer(RENTAL))).andExpect(status().isForbidden());
        mvc.perform(post(REDEEM).contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"305718\"}"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    // [상황] 부스가 맞는 코드를 넣는다.
    // [기대] 렌탈·기기·일련번호·기종·시점 ID를 받아 바로 촬영을 시작할 수 있다.
    @Test
    @DisplayName("부스가 맞는 코드를 넣으면 기기·렌탈·시점 ID를 받는다")
    void boothRedeems() throws Exception {
        when(service.redeem("305718")).thenReturn(new BoothCodeService.Target(RENTAL, "aAXQFO2Vz6ZTFbPctC2dq",
                "C02XG2JHJGH5", "MBA13-M2", "aJ3bYet82WYF8iVlHstT2", CheckpointType.OUT));
        mvc.perform(redeem("305718").with(booth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rentalId").value(RENTAL))
                .andExpect(jsonPath("$.deviceId").value("aAXQFO2Vz6ZTFbPctC2dq"))
                .andExpect(jsonPath("$.serial").value("C02XG2JHJGH5"))
                .andExpect(jsonPath("$.modelCode").value("MBA13-M2"))
                .andExpect(jsonPath("$.checkpointId").value("aJ3bYet82WYF8iVlHstT2"))
                .andExpect(jsonPath("$.checkpointType").value("OUT"));
    }

    // [상황] 5자리, 글자 섞인 코드, 모르는 칸(rentalId)을 붙인 요청.
    // [기대] 모두 400(서비스까지 가지 않음).
    //        ※ 응답 코드: 400 = 요청 내용이 형식에 맞지 않음
    @Test
    @DisplayName("코드가 6자리 숫자가 아니거나 모르는 필드가 있으면 400 VALIDATION_FAILED")
    void redeemValidation() throws Exception {
        mvc.perform(redeem("30571").with(booth())).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mvc.perform(redeem("30571a").with(booth())).andExpect(status().isBadRequest());
        mvc.perform(post(REDEEM).with(booth()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"305718\",\"rentalId\":\"x\"}")).andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    // [상황] 틀린 코드, 만료·이미 쓴 코드, 틀린 시도가 몰려 막힌 상태.
    // [기대] 틀림 400 BOOTH_CODE_INVALID, 만료 410, 막힘 429와 42초 뒤 다시(Retry-After).
    //        ※ 응답 코드: 400 = 요청 내용이 형식에 맞지 않음, 410 = 이미 쓰였거나 만료되어 더는 쓸 수 없음, 429 = 요청이 너무 잦아 잠시 막음
    @Test
    @DisplayName("틀린 코드는 400 BOOTH_CODE_INVALID, 만료·사용된 코드는 410, 막히면 429와 Retry-After")
    void redeemErrors() throws Exception {
        when(service.redeem("111111")).thenThrow(new DomainException(ErrorCode.BOOTH_CODE_INVALID, "틀림"));
        when(service.redeem("222222")).thenThrow(new DomainException(ErrorCode.BOOTH_CODE_EXPIRED, "만료"));
        when(service.redeem("333333")).thenThrow(new RateLimitedException("막힘", 42));
        mvc.perform(redeem("111111").with(booth())).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BOOTH_CODE_INVALID"));
        mvc.perform(redeem("222222").with(booth())).andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("BOOTH_CODE_EXPIRED"));
        mvc.perform(redeem("333333").with(booth())).andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "42"))
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
    }

    // [상황] 찍을 시점이 아직 없는 렌탈에 발급한다.
    // [기대] 409 CHECKPOINT_ORDER_VIOLATION(출고 시점부터 시작해야 함).
    //        ※ 응답 코드: 409 = 지금 상태와 맞지 않아 거절(이미 있음·순서 어긋남 등)
    @Test
    @DisplayName("찍을 시점이 없는 렌탈에 발급하면 409 CHECKPOINT_ORDER_VIOLATION")
    void issueConflict() throws Exception {
        when(service.issue(RENTAL)).thenThrow(new DomainException(ErrorCode.CHECKPOINT_ORDER_VIOLATION, "출고 먼저"));
        mvc.perform(post("/api/v1/rentals/" + RENTAL + "/booth-code").with(admin()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CHECKPOINT_ORDER_VIOLATION"));
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder redeem(String code) {
        return post(REDEEM).contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"" + code + "\"}");
    }
}
