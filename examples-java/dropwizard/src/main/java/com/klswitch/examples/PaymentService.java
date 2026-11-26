package com.klswitch.examples;

import java.util.Map;

/** Dummy downstream service — stands in for real payment processing. */
public final class PaymentService {

    public Map<String, Object> collect(String id, Map<String, Object> body) {
        Object amount = body == null ? null : body.get("amount");
        return Map.of("paymentId", id, "amount", amount == null ? 0 : amount, "captured", true);
    }
}
