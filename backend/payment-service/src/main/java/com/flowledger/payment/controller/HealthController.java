package com.flowledger.payment.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/payment")
public class HealthController {

    @GetMapping("/health")
    public Map<String, String> getHealth() {
        return Map.of(
                "status", "UP",
                "service", "payment-service"
        );
    }
}
