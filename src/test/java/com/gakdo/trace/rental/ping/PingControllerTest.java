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
@WebMvcTest(controllers = PingController.class, properties = {"app.commit=test-sha", "app.version=0.1.0"})
@Import(SecurityConfig.class)
class PingControllerTest {

    @Autowired
    private MockMvc mockMvc;

    // [상황] 로그인 없이 점검 주소를 부른다.
    // [기대] 서비스 이름 rental-server·버전·상태·배포 커밋을 돌려준다.
    @Test
    @DisplayName("ping은 로그인 없이 열리고 service·version·commit을 돌려준다")
    void ping() throws Exception {
        mockMvc.perform(get("/api/v1/rentals/ping"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.service").value("rental-server"))
                .andExpect(jsonPath("$.version").value("0.1.0"))
                .andExpect(jsonPath("$.status").value("ok"))
                .andExpect(jsonPath("$.commit").value("test-sha"));
    }
}
