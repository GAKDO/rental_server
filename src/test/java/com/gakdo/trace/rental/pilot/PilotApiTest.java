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

// (도구) @WebMvcTest = 서버 전체 대신 지정한 컨트롤러(PilotController)만 띄우는 가벼운 API 테스트. WebTestBase로 운영 보안 규칙도 함께 띄운다
@WebMvcTest(controllers = PilotController.class)
class PilotApiTest extends WebTestBase {

    // 등록 API 주소
    private static final String PATH = "/api/v1/pilot/participants";
    // 요청에 넣을 기기 공개 ID (21자)
    private static final String DEVICE = "aAXQFO2Vz6ZTFbPctC2dq";

    // (도구) MockMvc = 진짜 네트워크 없이 서버에 HTTP 요청을 보낸 것처럼 흉내 내는 도구. @Autowired = 스프링이 만들어 넣어 준다
    @Autowired
    private MockMvc mvc;

    // (도구) @MockitoBean = 진짜 서비스 대신 가짜(목) 서비스를 스프링에 끼워 넣는다. 컨트롤러(API 입구)만 따로 시험하려고
    @MockitoBean
    private PilotService service;

    // 도우미: 받은 별칭으로 "참가자 등록" POST 요청을 만든다 (기기·동의 시각은 고정)
    private static MockHttpServletRequestBuilder create(String alias) {
        // 등록 주소로 JSON 본문(alias, deviceId, consentAt)을 담은 POST 요청을 만든다
        return post(PATH).contentType(MediaType.APPLICATION_JSON).content("{\"alias\":\"" + alias
                + "\",\"deviceId\":\"" + DEVICE + "\",\"consentAt\":\"2026-11-11T14:00:00+09:00\"}");
    }

    // [상황] 관리자가 별칭 P01·기기·동의 시각으로 참가자를 등록한다.
    // [기대] 201과 참가자 ID를 받는다. 실명은 받지 않는다.
    //        ※ 응답 코드: 201 = 새로 만들어짐
    // (도구) @Test = JUnit에게 "이 메서드가 테스트 하나"라고 알리는 표시. @DisplayName = 결과 화면에 보일 한글 이름
    @Test
    @DisplayName("관리자가 별칭·기기·동의 시각으로 등록하면 201과 참여자 ID를 받는다(실명은 받지 않는다)")
    void adminRegisters() throws Exception {
        // (도구) when(가짜.메서드(값)).thenReturn(결과) = "가짜에게 이 질문이 오면 이 결과를 돌려줘"라고 미리 정해 두는 것. eq(값) = 정확히 이 값, any() = 아무 값
        // 준비: 가짜 서비스가 별칭 P01·이 기기로 등록 요청을 받으면 등록 결과(참가자 ID·별칭·기기·동의 시각·만든 시각)를 돌려주게 한다
        when(service.register(eq("P01"), eq(DEVICE), any())).thenReturn(new PilotService.Registered(
                "fBSvifsp4FWaM07ql1SET", "P01", DEVICE, OffsetDateTime.parse("2026-11-11T05:00:00Z"),
                OffsetDateTime.parse("2026-11-11T05:01:00Z")));
        // (도구) perform(요청) = 요청을 보낸다. with(admin()) = 관리자 로그인 토큰을 붙인다. andExpect(...) = 응답이 이래야 한다는 검사
        // 실행: 관리자로 P01 등록 요청을 보낸다
        mvc.perform(create("P01").with(admin()))
                // 확인: 응답 코드가 201(새로 만들어짐)이어야 한다
                .andExpect(status().isCreated())
                // (도구) jsonPath("$.칸") = 응답 JSON에서 그 칸의 값을 꺼낸다
                // 확인: 응답의 참가자 ID가 서비스가 돌려준 값이어야 한다
                .andExpect(jsonPath("$.participantId").value("fBSvifsp4FWaM07ql1SET"))
                // 확인: 응답의 별칭이 P01이어야 한다
                .andExpect(jsonPath("$.alias").value("P01"))
                // 확인: 응답의 기기 ID가 보낸 기기 ID여야 한다
                .andExpect(jsonPath("$.deviceId").value(DEVICE));
    }

    // [상황] 이미 쓰는 별칭, 실명("홍길동"), P00, P000을 넣는다.
    // [기대] 중복 409 PILOT_ALIAS_EXISTS, 나머지는 모양이 틀려 400.
    //        ※ 응답 코드: 409 = 지금 상태와 맞지 않아 거절(이미 있음·순서 어긋남 등), 400 = 요청 내용이 형식에 맞지 않음
    @Test
    @DisplayName("별칭이 겹치면 409 PILOT_ALIAS_EXISTS, 별칭 모양이 P01~P999가 아니면 400이다")
    void aliasRules() throws Exception {
        // 준비: 가짜 서비스가 별칭 P02로 등록 요청을 받으면
        when(service.register(eq("P02"), eq(DEVICE), any()))
                // "이미 쓰는 별칭" 에러를 던지게 한다 (thenThrow = 결과 대신 예외를 던짐)
                .thenThrow(new DomainException(ErrorCode.PILOT_ALIAS_EXISTS, "이미 쓰는 별칭입니다: P02"));
        // 실행: 관리자로 P02 등록 요청을 보낸다
        mvc.perform(create("P02").with(admin()))
                // 확인: 응답 코드가 409(충돌, 이미 있음)여야 한다
                .andExpect(status().isConflict())
                // 확인: 응답 JSON의 에러 코드가 PILOT_ALIAS_EXISTS여야 한다
                .andExpect(jsonPath("$.code").value("PILOT_ALIAS_EXISTS"));
        // 실행: 실명처럼 보이는 "홍길동"을 별칭으로 보낸다
        mvc.perform(create("홍길동").with(admin()))
                // 확인: 별칭 모양(P01~P999)이 아니니 응답 코드가 400이어야 한다 (실명 수집 방지)
                .andExpect(status().isBadRequest())
                // 확인: 에러 코드가 VALIDATION_FAILED(입력값 오류)여야 한다
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        // 실행+확인: P00은 범위(P01~) 밖이라 400이어야 한다
        mvc.perform(create("P00").with(admin())).andExpect(status().isBadRequest());
        // 실행+확인: P000도 범위 밖이라 400이어야 한다
        mvc.perform(create("P000").with(admin())).andExpect(status().isBadRequest());
    }

    // [상황] 고객·부스·로그인 없이 등록한다.
    // [기대] 고객·부스 403, 로그인 없음 401.
    //        ※ 응답 코드: 403 = 권한 없음(누구인지는 알지만 허락되지 않은 일), 401 = 로그인 필요(토큰이 없거나 만료·위조됨)
    @Test
    @DisplayName("고객·부스는 403, 토큰이 없으면 401이다")
    void adminOnly() throws Exception {
        // 실행+확인: 고객 토큰으로 등록하면 403(권한 없음)이어야 한다
        mvc.perform(create("P03").with(customer())).andExpect(status().isForbidden());
        // 실행+확인: 부스 토큰으로 등록해도 403이어야 한다
        mvc.perform(create("P03").with(booth())).andExpect(status().isForbidden());
        // 실행+확인: 토큰 없이 등록하면 401(로그인 필요)이어야 한다
        mvc.perform(create("P03")).andExpect(status().isUnauthorized());
        // (도구) verifyNoInteractions(가짜) = 그 가짜가 한 번도 불리지 않았어야 한다는 검사
        // 확인: 세 요청 모두 입구에서 막혔으니 서비스는 한 번도 불리지 않았어야 한다
        verifyNoInteractions(service);
    }
}
