package com.klswitch.examples;

import java.util.Map;

/** Dummy downstream service — stands in for a user store. */
public final class UserService {

    public Map<String, Object> fetch(String id) {
        return Map.of("userId", id, "name", "User " + id);
    }
}
