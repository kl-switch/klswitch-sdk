package com.klswitch.examples.spring;

import java.util.Map;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Note what is absent: no KLSwitch import, no annotation, no per-endpoint
 * wiring. A matched {@code block} rule returns before this class is reached.
 */
@RestController
public class PaymentsController {

    @PostMapping("/v1/payments/{id}")
    public Map<String, Object> collect(@PathVariable String id, @RequestBody Map<String, Object> body) {
        return Map.of(
                "ok", true,
                "paymentId", id,
                "amount", body.getOrDefault("amount", 0),
                "country", body.getOrDefault("country", "?"));
    }
}
