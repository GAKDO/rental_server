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

@WebMvcTest(controllers = PingController.class, properties = {"app.commit=test-sha", "app.version=0.1.0"})
@Import(SecurityConfig.class)
class PingControllerTest {

    @Autowired
    private MockMvc mockMvc;

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
