package com.flowledger.gateway.web;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

// The gateway's own health. Each service's health stays reachable at /api/v1/<service>/health.
@RestController
@RequestMapping("/api/v1/gateway")
public class HealthController {

    @GetMapping("/health")
    public Map<String, String> getHealth() {
        return Map.of(
                "status", "UP",
                "service", "api-gateway"
        );
    }
}
