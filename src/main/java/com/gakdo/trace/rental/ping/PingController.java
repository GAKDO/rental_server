package com.gakdo.trace.rental.ping;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PingController {

    @GetMapping("/api/v1/rentals/ping")
    public Map<String, String> ping() {
        return Map.of("service", "rental-server", "status", "ok");
    }
}
