// 이 파일이 확인하는 것: rental-server 점검 주소(/api/v1/rentals/ping).

package com.gakdo.trace.rental.ping;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import com.gakdo.trace.rental.common.infra.SecurityConfig;

/**
 * rental-server의 ping(점검) 주소 시험. 배포 뒤 "서버가 떴는지, 어떤 버전·커밋이 돌고 있는지" 확인할 때 부르는 주소다.
 * 컨트롤러만 띄우는 가벼운 시험(DB·메시지 큐 없음)이며, 커밋 값은 test-sha로 고정해 응답에 그대로 나오는지 본다.
 */
// (도구) @WebMvcTest = 서버 전체 대신 지정한 컨트롤러(PingController)만 띄우는 가벼운 테스트. properties로 커밋·버전 값을 고정한다
// (도구) @Import(SecurityConfig.class) = 운영과 같은 보안 규칙(로그인 필요 여부)을 함께 띄운다
@WebMvcTest(controllers = PingController.class, properties = {"app.commit=test-sha", "app.version=0.1.0"})
@Import(SecurityConfig.class)
class PingControllerTest {

    // (도구) MockMvc = 진짜 네트워크 없이 서버에 HTTP 요청을 보낸 것처럼 흉내 내는 도구. @Autowired = 스프링이 만들어 넣어 준다
    @Autowired
    private MockMvc mockMvc;

    // [상황] 로그인 없이 점검 주소를 부른다.
    // [기대] 서비스 이름 rental-server·버전·상태·배포 커밋을 돌려준다.
    // (도구) @Test = JUnit에게 "이 메서드가 테스트 하나"라고 알리는 표시. @DisplayName = 결과 화면에 보일 한글 이름
    @Test
    @DisplayName("ping은 로그인 없이 열리고 service·version·commit을 돌려준다")
    void ping() throws Exception {
        // (도구) perform(get(주소)) = 그 주소로 GET 요청을 보낸다. andExpect(...) = 응답이 이래야 한다는 검사를 줄줄이 붙인다
        // 실행: 로그인 토큰 없이 점검 주소로 GET 요청을 보낸다
        mockMvc.perform(get("/api/v1/rentals/ping"))
                // 확인: 응답 코드가 200(성공)이어야 한다 = 로그인 없이도 열린다
                .andExpect(status().isOk())
                // (도구) jsonPath("$.칸") = 응답 JSON에서 그 칸의 값을 꺼낸다. $ = JSON 맨 바깥
                // 확인: service 칸이 "rental-server"여야 한다 (어느 서버가 답했는지)
                .andExpect(jsonPath("$.service").value("rental-server"))
                // 확인: version 칸이 위에서 고정한 0.1.0이어야 한다
                .andExpect(jsonPath("$.version").value("0.1.0"))
                // 확인: status 칸이 "ok"여야 한다 (서버가 정상)
                .andExpect(jsonPath("$.status").value("ok"))
                // 확인: commit 칸이 위에서 고정한 test-sha여야 한다 (배포된 커밋이 그대로 나오는지)
                .andExpect(jsonPath("$.commit").value("test-sha"));
    }
}
