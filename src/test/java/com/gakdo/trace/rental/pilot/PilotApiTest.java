// 이 파일이 확인하는 것: 파일럿(실사용 시범) 참가자 등록 API. 실명 대신 별칭(P01~P999)만 받는다.

package com.gakdo.trace.rental.pilot;

import static com.gakdo.trace.rental.support.TestJwts.admin;
import static com.gakdo.trace.rental.support.TestJwts.booth;
import static com.gakdo.trace.rental.support.TestJwts.customer;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.gakdo.trace.rental.common.domain.DomainException;
import com.gakdo.trace.rental.common.domain.ErrorCode;
import com.gakdo.trace.rental.pilot.api.PilotController;
import com.gakdo.trace.rental.pilot.application.PilotService;
import com.gakdo.trace.rental.support.WebTestBase;

@WebMvcTest(controllers = PilotController.class)
class PilotApiTest extends WebTestBase {

    private static final String PATH = "/api/v1/pilot/participants";
    private static final String DEVICE = "aAXQFO2Vz6ZTFbPctC2dq";

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private PilotService service;

    private static MockHttpServletRequestBuilder create(String alias) {
        return post(PATH).contentType(MediaType.APPLICATION_JSON).content("{\"alias\":\"" + alias
                + "\",\"deviceId\":\"" + DEVICE + "\",\"consentAt\":\"2026-11-11T14:00:00+09:00\"}");
    }

    // [상황] 관리자가 별칭 P01·기기·동의 시각으로 참가자를 등록한다.
    // [기대] 201과 참가자 ID를 받는다. 실명은 받지 않는다.
    //        ※ 응답 코드: 201 = 새로 만들어짐
    @Test
    @DisplayName("관리자가 별칭·기기·동의 시각으로 등록하면 201과 참여자 ID를 받는다(실명은 받지 않는다)")
    void adminRegisters() throws Exception {
        when(service.register(eq("P01"), eq(DEVICE), any())).thenReturn(new PilotService.Registered(
                "fBSvifsp4FWaM07ql1SET", "P01", DEVICE, OffsetDateTime.parse("2026-11-11T05:00:00Z"),
                OffsetDateTime.parse("2026-11-11T05:01:00Z")));
        mvc.perform(create("P01").with(admin()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.participantId").value("fBSvifsp4FWaM07ql1SET"))
                .andExpect(jsonPath("$.alias").value("P01"))
                .andExpect(jsonPath("$.deviceId").value(DEVICE));
    }

    // [상황] 이미 쓰는 별칭, 실명("홍길동"), P00, P000을 넣는다.
    // [기대] 중복 409 PILOT_ALIAS_EXISTS, 나머지는 모양이 틀려 400.
    //        ※ 응답 코드: 409 = 지금 상태와 맞지 않아 거절(이미 있음·순서 어긋남 등), 400 = 요청 내용이 형식에 맞지 않음
    @Test
    @DisplayName("별칭이 겹치면 409 PILOT_ALIAS_EXISTS, 별칭 모양이 P01~P999가 아니면 400이다")
    void aliasRules() throws Exception {
        when(service.register(eq("P02"), eq(DEVICE), any()))
                .thenThrow(new DomainException(ErrorCode.PILOT_ALIAS_EXISTS, "이미 쓰는 별칭입니다: P02"));
        mvc.perform(create("P02").with(admin()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PILOT_ALIAS_EXISTS"));
        mvc.perform(create("홍길동").with(admin()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mvc.perform(create("P00").with(admin())).andExpect(status().isBadRequest());
        mvc.perform(create("P000").with(admin())).andExpect(status().isBadRequest());
    }

    // [상황] 고객·부스·로그인 없이 등록한다.
    // [기대] 고객·부스 403, 로그인 없음 401.
    //        ※ 응답 코드: 403 = 권한 없음(누구인지는 알지만 허락되지 않은 일), 401 = 로그인 필요(토큰이 없거나 만료·위조됨)
    @Test
    @DisplayName("고객·부스는 403, 토큰이 없으면 401이다")
    void adminOnly() throws Exception {
        mvc.perform(create("P03").with(customer())).andExpect(status().isForbidden());
        mvc.perform(create("P03").with(booth())).andExpect(status().isForbidden());
        mvc.perform(create("P03")).andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }
}
