package com.klswitch.examples.spring;

import com.klswitch.EvaluationResult;
import com.klswitch.servlet.KLSwitchFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * A {@code flag} action does not block, so this handler reads the result to
 * act on it — the one case where a handler touches KLSwitch at all.
 */
@RestController
public class UsersController {

    @GetMapping("/v1/users/{id}")
    public Map<String, Object> get(@PathVariable String id, HttpServletRequest request) {
        List<String> flags = List.of();
        if (request.getAttribute(KLSwitchFilter.RESULT_ATTR) instanceof EvaluationResult result) {
            flags = result.matched().stream()
                    .filter(m -> m.action() != null && "flag".equals(m.action().type()))
                    .map(m -> m.id())
                    .toList();
        }
        return Map.of("ok", true, "userId", id, "flags", flags);
    }
}
